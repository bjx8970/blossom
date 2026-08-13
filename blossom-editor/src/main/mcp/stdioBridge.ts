import { app } from 'electron'
import { request as httpRequest } from 'node:http'
import { ReadBuffer, serializeMessage } from '@modelcontextprotocol/sdk/shared/stdio.js'
import type { JSONRPCMessage } from '@modelcontextprotocol/sdk/types.js'
import { McpConfigStore } from './configStore'
import { MCP_HOST, MCP_PATH, MCP_PROTOCOL_VERSION } from './types'

const MAX_HTTP_RESPONSE_BYTES = 16 * 1024 * 1024

const jsonRpcError = (message: JSONRPCMessage, errorMessage: string): JSONRPCMessage | undefined => {
  if (!('id' in message)) return undefined
  return {
    jsonrpc: '2.0',
    id: message.id,
    error: { code: -32000, message: errorMessage }
  }
}

export const runMcpStdioBridge = async (): Promise<void> => {
  await app.whenReady()
  const store = new McpConfigStore()
  const config = await store.load()
  if (store.getStorageSecurity() !== 'secure') throw new Error('安全凭据存储不可用，无法启动 MCP stdio 桥')
  if (!config.enabled || config.port === 0) throw new Error('Blossom 桌面端 MCP 尚未启用')

  const readBuffer = new ReadBuffer({ maxBufferSize: 8 * 1024 * 1024 })
  let protocolVersion: string | undefined
  let sessionId: string | undefined
  let writeQueue = Promise.resolve()
  let requestQueue = Promise.resolve()

  const writeMessage = (message: JSONRPCMessage): Promise<void> => {
    writeQueue = writeQueue.then(
      () =>
        new Promise<void>((resolve, reject) => {
          process.stdout.write(serializeMessage(message), (error) => (error ? reject(error) : resolve()))
        })
    )
    return writeQueue
  }

  const postMessage = async (message: JSONRPCMessage): Promise<void> => {
    const requestBody = Buffer.from(JSON.stringify(message))
    try {
      const responseResult = await new Promise<{ text: string; sessionId?: string }>((resolve, reject) => {
        const request = httpRequest(
          {
            host: MCP_HOST,
            port: config.port,
            path: MCP_PATH,
            method: 'POST',
            headers: {
              Accept: 'application/json, text/event-stream',
              Authorization: `Bearer ${config.localBearer}`,
              'Content-Type': 'application/json',
              'Content-Length': String(requestBody.length),
              ...(protocolVersion ? { 'MCP-Protocol-Version': protocolVersion } : {}),
              ...(sessionId ? { 'Mcp-Session-Id': sessionId } : {})
            },
            timeout: 70_000
          },
          (response) => {
            const chunks: Buffer[] = []
            let length = 0
            response.on('data', (chunk: Buffer) => {
              length += chunk.length
              if (length > MAX_HTTP_RESPONSE_BYTES) {
                response.destroy(new Error('MCP HTTP 响应过大'))
                return
              }
              chunks.push(chunk)
            })
            response.once('error', reject)
            response.once('end', () => {
              const text = Buffer.concat(chunks).toString('utf8')
              if ((response.statusCode || 500) >= 400) {
                reject(new Error(`Blossom MCP HTTP ${response.statusCode || 500}`))
              } else {
                const returnedSessionId = response.headers['mcp-session-id']
                resolve({ text, sessionId: typeof returnedSessionId === 'string' ? returnedSessionId : undefined })
              }
            })
          }
        )
        request.once('timeout', () => request.destroy(new Error('Blossom MCP 请求超时')))
        request.once('error', reject)
        request.end(requestBody)
      })
      if (responseResult.sessionId) sessionId = responseResult.sessionId
      const responseText = responseResult.text
      if (!responseText) return
      const response = JSON.parse(responseText) as JSONRPCMessage
      if ('result' in response) {
        const result = response.result as { protocolVersion?: unknown }
        if (typeof result?.protocolVersion === 'string') protocolVersion = MCP_PROTOCOL_VERSION
      }
      await writeMessage(response)
    } catch (error) {
      const response = jsonRpcError(message, error instanceof Error ? error.message : 'Blossom MCP 桥接失败')
      if (response) await writeMessage(response)
    }
  }

  await new Promise<void>((resolve, reject) => {
    process.stdin.on('data', (chunk: Buffer) => {
      try {
        readBuffer.append(chunk)
        let message = readBuffer.readMessage()
        while (message) {
          const nextMessage = message
          const operation = requestQueue.then(() => postMessage(nextMessage))
          requestQueue = operation.catch(() => undefined)
          message = readBuffer.readMessage()
        }
      } catch (error) {
        reject(error)
      }
    })
    process.stdin.once('error', reject)
    process.stdin.once('end', () => {
      void requestQueue.then(async () => {
        if (sessionId) {
          await new Promise<void>((done) => {
            const request = httpRequest({
              host: MCP_HOST,
              port: config.port,
              path: MCP_PATH,
              method: 'DELETE',
              headers: {
                Authorization: `Bearer ${config.localBearer}`,
                'MCP-Protocol-Version': MCP_PROTOCOL_VERSION,
                'Mcp-Session-Id': sessionId
              },
              timeout: 2_000
            })
            request.once('response', (response) => {
              response.resume()
              response.once('end', done)
            })
            request.once('timeout', () => request.destroy())
            request.once('error', () => done())
            request.end()
          })
        }
        resolve()
      })
    })
  })
}
