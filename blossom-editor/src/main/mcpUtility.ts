import { startLocalMcpHttpServer, type LocalMcpHttpServer } from './mcp/httpServer'
import type { MainToMcpUtilityMessage, McpUtilityToMainMessage } from './mcp/types'

const parentPort = process.parentPort
let server: LocalMcpHttpServer | undefined
let shuttingDown = false

const send = (message: McpUtilityToMainMessage): void => parentPort?.postMessage(message)

const stop = async (): Promise<void> => {
  if (shuttingDown) return
  shuttingDown = true
  await server?.close().catch(() => undefined)
  server = undefined
  send({ type: 'stopped' })
  process.exit(0)
}

if (!parentPort) {
  process.exit(1)
} else {
  let messageQueue = Promise.resolve()
  const handleMessage = async (message: MainToMcpUtilityMessage): Promise<void> => {
    try {
      if (message.type === 'start') {
        if (server) return
        server = await startLocalMcpHttpServer({
          port: message.port,
          localBearer: message.localBearer,
          backendAuth: message.backendAuth,
          onActivity: () => send({ type: 'last-call', at: new Date().toISOString() }),
          onBackendAuthInvalid: () => send({ type: 'backend-auth-invalid' })
        })
        send({ type: 'ready', port: message.port })
      } else if (message.type === 'update-local-bearer') {
        server?.updateLocalBearer(message.localBearer)
      } else if (message.type === 'update-backend-auth') {
        server?.updateBackendAuth(message.backendAuth)
      } else if (message.type === 'clear-backend-auth') {
        await server?.clearBackendAuth()
        send({ type: 'backend-auth-cleared', requestId: message.requestId })
      } else if (message.type === 'shutdown') {
        await stop()
      }
    } catch (error) {
      const typedError = error as NodeJS.ErrnoException
      send({ type: 'error', code: typedError.code || 'MCP_UTILITY_ERROR', message: typedError.message })
      if (message.type === 'start') await stop()
    }
  }
  parentPort.on('message', (event) => {
    const message = event.data as MainToMcpUtilityMessage
    messageQueue = messageQueue.then(() => handleMessage(message))
  })
  process.once('SIGTERM', () => void stop())
  process.once('SIGINT', () => void stop())
}
