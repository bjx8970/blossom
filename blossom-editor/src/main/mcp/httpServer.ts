import { randomUUID, timingSafeEqual } from 'node:crypto'
import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http'
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js'
import { isInitializeRequest } from '@modelcontextprotocol/sdk/types.js'
import { createBlossomMcpServer } from './tools'
import { MCP_HOST, MCP_PATH, MCP_PROTOCOL_VERSION, type McpBackendAuth } from './types'

// 比 2 MiB Markdown 上限预留协议字段空间，同时保持严格的总请求体硬上限。
const MAX_BODY_BYTES = Math.floor(2.5 * 1024 * 1024)
const MAX_CONCURRENCY = 8
const MAX_SESSIONS = 32
const SESSION_IDLE_MS = 30 * 60_000
const RATE_CAPACITY = 60
const RATE_REFILL_PER_MS = RATE_CAPACITY / 60_000

const sendHttpError = (response: ServerResponse, status: number, message: string): void => {
  if (response.headersSent) return
  response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' })
  response.end(JSON.stringify({ error: message }))
}

const tokenMatches = (authorization: string | undefined, expected: string): boolean => {
  const prefix = 'Bearer '
  if (!authorization?.startsWith(prefix)) return false
  const actual = Buffer.from(authorization.slice(prefix.length))
  const wanted = Buffer.from(expected)
  if (actual.length !== wanted.length) return false
  return timingSafeEqual(actual, wanted)
}

const readJsonBody = async (request: IncomingMessage): Promise<unknown> => {
  const declaredLength = Number(request.headers['content-length'] || 0)
  if (declaredLength > MAX_BODY_BYTES) throw Object.assign(new Error('请求体过大'), { status: 413 })
  const chunks: Buffer[] = []
  let length = 0
  for await (const chunk of request) {
    const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)
    length += buffer.length
    if (length > MAX_BODY_BYTES) throw Object.assign(new Error('请求体过大'), { status: 413 })
    chunks.push(buffer)
  }
  if (length === 0) return undefined
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8'))
  } catch {
    throw Object.assign(new Error('请求体不是有效 JSON'), { status: 400 })
  }
}

const pinProtocolVersion = (body: unknown): unknown => {
  if (!body || typeof body !== 'object' || Array.isArray(body)) return body
  const message = body as Record<string, unknown>
  if (message.method !== 'initialize' || !message.params || typeof message.params !== 'object' || Array.isArray(message.params)) {
    return body
  }
  return {
    ...message,
    params: { ...(message.params as Record<string, unknown>), protocolVersion: MCP_PROTOCOL_VERSION }
  }
}

export interface LocalMcpHttpServer {
  updateLocalBearer(token: string): void
  updateBackendAuth(auth: McpBackendAuth): void
  clearBackendAuth(): Promise<void>
  close(): Promise<void>
}

export const startLocalMcpHttpServer = async (options: {
  port: number
  localBearer: string
  backendAuth?: McpBackendAuth
  onActivity: () => void
  onBackendAuthInvalid: () => void
}): Promise<LocalMcpHttpServer> => {
  let localBearer = options.localBearer
  let backendAuth = options.backendAuth
  let activeRequests = 0
  let rateTokens = RATE_CAPACITY
  let lastRefill = Date.now()
  let pendingInitializations = 0

  type SessionServer = ReturnType<typeof createBlossomMcpServer>
  interface McpSession {
    server: SessionServer
    transport: StreamableHTTPServerTransport
    lastSeenAt: number
  }
  const sessions = new Map<string, McpSession>()

  const refillRateTokens = (): boolean => {
    const now = Date.now()
    rateTokens = Math.min(RATE_CAPACITY, rateTokens + (now - lastRefill) * RATE_REFILL_PER_MS)
    lastRefill = now
    if (rateTokens < 1) return false
    rateTokens -= 1
    return true
  }

  const closeSessions = async (entries: McpSession[] = [...sessions.values()]): Promise<void> => {
    for (const [sessionId, entry] of sessions) {
      if (entries.includes(entry)) sessions.delete(sessionId)
    }
    await Promise.allSettled(entries.map((entry) => entry.server.close()))
  }

  const idleSessionTimer = setInterval(() => {
    const cutoff = Date.now() - SESSION_IDLE_MS
    const expired = [...sessions.values()].filter((entry) => entry.lastSeenAt < cutoff)
    if (expired.length > 0) void closeSessions(expired)
  }, 60_000)
  idleSessionTimer.unref()

  const createSession = async (request: IncomingMessage, response: ServerResponse, body: unknown): Promise<void> => {
    if (sessions.size + pendingInitializations >= MAX_SESSIONS) {
      sendHttpError(response, 429, 'Too Many MCP Sessions')
      return
    }
    pendingInitializations += 1
    let entry: McpSession | undefined
    try {
      const transport = new StreamableHTTPServerTransport({
        sessionIdGenerator: randomUUID,
        enableJsonResponse: true,
        onsessioninitialized: (sessionId) => {
          if (!entry) throw new Error('MCP session initialized before server setup completed')
          sessions.set(sessionId, entry)
        },
        onsessionclosed: (sessionId) => {
          sessions.delete(sessionId)
        }
      })
      const server = createBlossomMcpServer({
        getBackendAuth: () => backendAuth,
        onActivity: options.onActivity,
        onBackendAuthInvalid: options.onBackendAuthInvalid
      })
      entry = { server, transport, lastSeenAt: Date.now() }
      transport.onclose = () => {
        const sessionId = transport.sessionId
        if (sessionId && sessions.get(sessionId) === entry) sessions.delete(sessionId)
      }
      await server.connect(transport)
      await transport.handleRequest(request, response, body)
      if (!transport.sessionId) await server.close()
    } catch (error) {
      if (entry) await entry.server.close().catch(() => undefined)
      throw error
    } finally {
      pendingInitializations -= 1
    }
  }

  const expectedHost = `${MCP_HOST}:${options.port}`
  const httpServer: Server = createServer(async (request, response) => {
    response.setHeader('Cache-Control', 'no-store')
    if (request.url?.split('?')[0] !== MCP_PATH) return sendHttpError(response, 404, 'Not Found')
    if (request.headers.host !== expectedHost) return sendHttpError(response, 403, 'Forbidden Host')
    if (request.headers.origin !== undefined) return sendHttpError(response, 403, 'Forbidden Origin')
    if (!tokenMatches(request.headers.authorization, localBearer)) return sendHttpError(response, 401, 'Unauthorized')
    if (!refillRateTokens()) return sendHttpError(response, 429, 'Too Many Requests')
    if (activeRequests >= MAX_CONCURRENCY) return sendHttpError(response, 429, 'Too Many Concurrent Requests')
    if (!['GET', 'POST', 'DELETE'].includes(request.method || '')) return sendHttpError(response, 405, 'Method Not Allowed')
    const protocolVersion = request.headers['mcp-protocol-version']
    if (protocolVersion !== undefined && protocolVersion !== MCP_PROTOCOL_VERSION) {
      return sendHttpError(response, 400, `MCP protocol version must be ${MCP_PROTOCOL_VERSION}`)
    }
    if (request.method === 'POST' && request.headers['content-type']?.split(';', 1)[0].trim().toLowerCase() !== 'application/json') {
      return sendHttpError(response, 415, 'Unsupported Media Type')
    }

    activeRequests += 1
    try {
      const body = request.method === 'POST' ? pinProtocolVersion(await readJsonBody(request)) : undefined
      const isInitializing = request.method === 'POST' && isInitializeRequest(body)
      if (!isInitializing && protocolVersion !== MCP_PROTOCOL_VERSION) {
        return sendHttpError(response, 400, `MCP protocol version must be ${MCP_PROTOCOL_VERSION}`)
      }
      const rawSessionId = request.headers['mcp-session-id']
      const sessionId = typeof rawSessionId === 'string' ? rawSessionId : undefined
      if (rawSessionId !== undefined && sessionId === undefined) {
        return sendHttpError(response, 400, 'Invalid MCP Session ID')
      }
      if (sessionId) {
        const session = sessions.get(sessionId)
        if (!session) return sendHttpError(response, 404, 'MCP Session Not Found')
        session.lastSeenAt = Date.now()
        await session.transport.handleRequest(request, response, body)
      } else if (isInitializing) {
        await createSession(request, response, body)
      } else {
        sendHttpError(response, 400, 'MCP Session ID Required')
      }
    } catch (error) {
      const status = Number((error as { status?: number }).status || 500)
      sendHttpError(response, status, status === 500 ? 'Internal Server Error' : (error as Error).message)
    } finally {
      activeRequests -= 1
    }
  })

  httpServer.requestTimeout = 70_000
  httpServer.headersTimeout = 10_000
  httpServer.keepAliveTimeout = 5_000
  httpServer.maxHeadersCount = 64

  await new Promise<void>((resolve, reject) => {
    const onError = (error: Error): void => reject(error)
    httpServer.once('error', onError)
    httpServer.listen(options.port, MCP_HOST, () => {
      httpServer.off('error', onError)
      resolve()
    })
  })

  return {
    updateLocalBearer(token: string) {
      localBearer = token
      void closeSessions()
    },
    updateBackendAuth(auth: McpBackendAuth) {
      backendAuth = auth
      void closeSessions()
    },
    async clearBackendAuth() {
      backendAuth = undefined
      await closeSessions()
    },
    async close() {
      clearInterval(idleSessionTimer)
      await closeSessions()
      await new Promise<void>((resolve) => {
        httpServer.close(() => resolve())
        httpServer.closeAllConnections?.()
      })
    }
  }
}
