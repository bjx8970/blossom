import assert from 'node:assert/strict'
import { createServer, type Server } from 'node:http'
import { createServer as createNetServer } from 'node:net'
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'
import { DEVICE_TOKEN_ROTATE_AHEAD_MS, DeviceTokenRotationTimer, MAX_DEVICE_TOKEN_TIMER_DELAY_MS } from '../src/main/mcp/deviceTokenRotationTimer'
import { startLocalMcpHttpServer } from '../src/main/mcp/httpServer'
import { MCP_PROTOCOL_VERSION } from '../src/main/mcp/types'

const LOCAL_BEARER = 'contract-test-local-bearer'
const DEVICE_BEARER = 'contract-test-device-bearer'

const freePort = async (): Promise<number> =>
  await new Promise((resolve, reject) => {
    const probe = createNetServer()
    probe.once('error', reject)
    probe.listen(0, '127.0.0.1', () => {
      const address = probe.address()
      const port = typeof address === 'object' && address ? address.port : 0
      probe.close((error) => (error ? reject(error) : resolve(port)))
    })
  })

const listen = async (server: Server): Promise<number> =>
  await new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => {
      const address = server.address()
      resolve(typeof address === 'object' && address ? address.port : 0)
    })
  })

const close = async (server: Server): Promise<void> => await new Promise((resolve) => server.close(() => resolve()))

const verifyDeviceTokenRotationTimer = (): void => {
  interface FakeTimer {
    callback: () => void
    delayMs: number
    cleared: boolean
  }
  let now = 0
  let calls = 0
  const timers: FakeTimer[] = []
  const rotationTimer = new DeviceTokenRotationTimer(
    () => now,
    ((callback: () => void, delayMs: number) => {
      const timer = { callback, delayMs, cleared: false }
      timers.push(timer)
      return timer
    }) as unknown as typeof setTimeout,
    ((timer: FakeTimer) => {
      timer.cleared = true
    }) as unknown as typeof clearTimeout
  )
  const activeTimer = (): FakeTimer => {
    const timer = [...timers].reverse().find((item) => !item.cleared)
    assert.ok(timer)
    return timer
  }

  const ninetyDays = 90 * 24 * 60 * 60 * 1_000
  rotationTimer.schedule(new Date(now + ninetyDays).toISOString(), () => (calls += 1))
  const longDelay = activeTimer()
  assert.equal(longDelay.delayMs, MAX_DEVICE_TOKEN_TIMER_DELAY_MS)
  now += longDelay.delayMs
  longDelay.cleared = true
  longDelay.callback()
  assert.equal(calls, 0)
  assert.equal(activeTimer().delayMs, MAX_DEVICE_TOKEN_TIMER_DELAY_MS)

  const staleTimer = activeTimer()
  rotationTimer.clear()
  assert.equal(staleTimer.cleared, true)
  staleTimer.callback()
  assert.equal(calls, 0)

  rotationTimer.schedule(new Date(now + DEVICE_TOKEN_ROTATE_AHEAD_MS).toISOString(), () => (calls += 1))
  const dueTimer = activeTimer()
  assert.equal(dueTimer.delayMs, 0)
  dueTimer.cleared = true
  dueTimer.callback()
  assert.equal(calls, 1)

  rotationTimer.schedule(new Date(now + DEVICE_TOKEN_ROTATE_AHEAD_MS).toISOString(), () => (calls += 10))
  const replacedTimer = activeTimer()
  rotationTimer.schedule(new Date(now + DEVICE_TOKEN_ROTATE_AHEAD_MS).toISOString(), () => (calls += 100))
  const currentTimer = activeTimer()
  assert.equal(replacedTimer.cleared, true)
  replacedTimer.callback()
  rotationTimer.clear()
  assert.equal(currentTimer.cleared, true)
  assert.equal(calls, 1)

  rotationTimer.schedule(new Date(now + DEVICE_TOKEN_ROTATE_AHEAD_MS).toISOString(), () => (calls += 1), 3_600_000)
  const retryTimer = activeTimer()
  assert.equal(retryTimer.delayMs, 3_600_000)
  rotationTimer.clear()
}

const backend = createServer((request, response) => {
  response.setHeader('Content-Type', 'application/json; charset=utf-8')
  if (request.headers.authorization !== `Bearer ${DEVICE_BEARER}`) {
    response.end(JSON.stringify({ code: 'AI-AUTH-INVALID', msg: 'invalid token', data: null }))
    return
  }
  if (request.method === 'GET' && request.url === '/api/ai/v1/folders') {
    response.end(
      JSON.stringify({
        code: '20000',
        msg: '成功',
        data: [{ id: 0, parentId: 0, name: '根目录', path: '/', sort: 0 }]
      })
    )
    return
  }
  if (request.method === 'GET' && request.url === '/api/ai/v1/notes/401') {
    response.end(JSON.stringify({ code: 'AUTH-40101', msg: 'device token expired', data: null }))
    return
  }
  if (request.method === 'GET' && request.url === '/api/ai/v1/notes/403') {
    response.end(JSON.stringify({ code: 'AUTH-40302', msg: 'scope denied', data: null }))
    return
  }
  if (request.method === 'PATCH' && request.url === '/api/ai/v1/notes/1') {
    response.end(JSON.stringify({ code: 'ARTICLE-CONFLICT', msg: 'revision conflict', data: null }))
    return
  }
  response.end(JSON.stringify({ code: '20000', msg: '成功', data: { items: [] } }))
})

const main = async (): Promise<void> => {
  let localServer: Awaited<ReturnType<typeof startLocalMcpHttpServer>> | undefined
  let client: Client | undefined
  try {
    verifyDeviceTokenRotationTimer()
    const backendPort = await listen(backend)
    const mcpPort = await freePort()
    localServer = await startLocalMcpHttpServer({
      port: mcpPort,
      localBearer: LOCAL_BEARER,
      backendAuth: {
        serverUrl: `http://127.0.0.1:${backendPort}`,
        token: DEVICE_BEARER,
        userId: '1',
        username: 'contract-test'
      },
      onActivity: () => undefined,
      onBackendAuthInvalid: () => undefined
    })

    const endpoint = `http://127.0.0.1:${mcpPort}/mcp`
    const unauthorized = await fetch(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize', params: {} })
    })
    assert.equal(unauthorized.status, 401)
    const browserRequest = await fetch(endpoint, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${LOCAL_BEARER}`,
        'Content-Type': 'application/json',
        Origin: 'https://example.invalid'
      },
      body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize', params: {} })
    })
    assert.equal(browserRequest.status, 403)

    const transport = new StreamableHTTPClientTransport(new URL(endpoint), {
      requestInit: { headers: { Authorization: `Bearer ${LOCAL_BEARER}` } }
    })
    client = new Client({ name: 'blossom-contract-test', version: '1.0.0' })
    await client.connect(transport)
    assert.equal(transport.protocolVersion, MCP_PROTOCOL_VERSION)

    const tools = await client.listTools()
    assert.deepEqual(
      tools.tools.map((tool) => tool.name),
      ['list_folders', 'list_notes', 'search_notes', 'get_note', 'create_note', 'update_note']
    )

    const folders = await client.callTool({ name: 'list_folders', arguments: {} })
    assert.equal(folders.isError, undefined)
    assert.equal((folders.structuredContent as { untrustedUserContent?: boolean }).untrustedUserContent, true)

    const conflict = await client.callTool({
      name: 'update_note',
      arguments: { id: 1, expectedRevision: 1, clientRequestId: 'contract-1', title: 'new title' }
    })
    assert.equal(conflict.isError, true)
    assert.equal((conflict.structuredContent as { error?: { code?: string } }).error?.code, 'REVISION_CONFLICT')

    const expired = await client.callTool({ name: 'get_note', arguments: { id: 401 } })
    assert.equal(expired.isError, true)
    assert.equal((expired.structuredContent as { error?: { code?: string } }).error?.code, 'NOT_LOGGED_IN')

    const denied = await client.callTool({ name: 'get_note', arguments: { id: 403 } })
    assert.equal(denied.isError, true)
    assert.equal((denied.structuredContent as { error?: { code?: string } }).error?.code, 'SCOPE_DENIED')

    await transport.terminateSession()
    console.log('MCP_CONTRACT_TEST_OK')
  } finally {
    await client?.close().catch(() => undefined)
    await localServer?.close().catch(() => undefined)
    await close(backend).catch(() => undefined)
  }
}

void main().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
