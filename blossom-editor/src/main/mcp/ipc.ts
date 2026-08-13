import { app, clipboard, dialog, ipcMain, type BrowserWindow, type IpcMainInvokeEvent } from 'electron'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'
import type { McpCopyConfigKind, McpRendererAuth } from './types'
import { McpServiceManager } from './serviceManager'

export const MCP_IPC_CHANNELS = {
  getStatus: 'mcp:get-status',
  setEnabled: 'mcp:set-enabled',
  setPort: 'mcp:set-port',
  rotateToken: 'mcp:rotate-token',
  copyConfig: 'mcp:copy-config',
  syncAuth: 'mcp:sync-auth',
  clearAuth: 'mcp:clear-auth'
} as const

const isCopyConfigKind = (value: unknown): value is McpCopyConfigKind => value === 'http' || value === 'stdio'

const isTrustedRendererUrl = (rawUrl: string): boolean => {
  try {
    const actual = new URL(rawUrl)
    const developmentUrl = !app.isPackaged ? process.env['ELECTRON_RENDERER_URL'] : undefined
    if (developmentUrl) return actual.origin === new URL(developmentUrl).origin
    const expected = new URL(pathToFileURL(join(__dirname, '../renderer/index.html')).href)
    return actual.protocol === 'file:' && actual.host === expected.host && actual.pathname === expected.pathname
  } catch {
    return false
  }
}

const validateAuth = (value: unknown): McpRendererAuth => {
  if (!value || typeof value !== 'object') throw new Error('MCP 登录上下文无效')
  const auth = value as Partial<McpRendererAuth>
  if (typeof auth.serverUrl !== 'string' || auth.serverUrl.length < 1 || auth.serverUrl.length > 2048) {
    throw new Error('MCP 服务地址无效')
  }
  if (typeof auth.token !== 'string' || auth.token.length < 1 || auth.token.length > 16_384) throw new Error('MCP 登录令牌无效')
  if ((typeof auth.userId !== 'string' && typeof auth.userId !== 'number') || String(auth.userId).length > 128) {
    throw new Error('MCP 用户 ID 无效')
  }
  if (typeof auth.username !== 'string' || auth.username.length < 1 || auth.username.length > 256) {
    throw new Error('MCP 用户名无效')
  }
  return auth as McpRendererAuth
}

export const registerMcpIpc = (manager: McpServiceManager, getMainWindow: () => BrowserWindow | undefined): void => {
  const assertTrustedSender = (event: IpcMainInvokeEvent): BrowserWindow => {
    const mainWindow = getMainWindow()
    if (
      !mainWindow ||
      mainWindow.isDestroyed() ||
      event.sender.id !== mainWindow.webContents.id ||
      event.senderFrame !== mainWindow.webContents.mainFrame ||
      !isTrustedRendererUrl(event.senderFrame.url)
    ) {
      throw new Error('拒绝来自非主窗口的 MCP IPC 请求')
    }
    return mainWindow
  }

  ipcMain.handle(MCP_IPC_CHANNELS.getStatus, (event) => {
    assertTrustedSender(event)
    return manager.getStatus()
  })
  ipcMain.handle(MCP_IPC_CHANNELS.setEnabled, async (event, enabled: unknown) => {
    assertTrustedSender(event)
    if (typeof enabled !== 'boolean') throw new Error('MCP enabled 必须为 boolean')
    return await manager.setEnabled(enabled)
  })
  ipcMain.handle(MCP_IPC_CHANNELS.setPort, async (event, port: unknown) => {
    assertTrustedSender(event)
    if (typeof port !== 'number') throw new Error('MCP port 必须为 number')
    return await manager.setPort(port)
  })
  ipcMain.handle(MCP_IPC_CHANNELS.rotateToken, async (event) => {
    assertTrustedSender(event)
    return await manager.rotateLocalBearer()
  })
  ipcMain.handle(MCP_IPC_CHANNELS.copyConfig, async (event, requestedKind: unknown) => {
    const mainWindow = assertTrustedSender(event)
    const kind = requestedKind === undefined ? 'stdio' : requestedKind
    if (!isCopyConfigKind(kind)) throw new Error('未知的 MCP 配置类型')
    if (kind === 'http') {
      const confirmation = await dialog.showMessageBox(mainWindow, {
        type: 'warning',
        title: '复制 MCP HTTP 凭据',
        message: 'HTTP 配置包含可访问本机 Blossom MCP 的密钥。仅粘贴到你信任的 AI 工具中。',
        buttons: ['取消', '复制'],
        defaultId: 0,
        cancelId: 0,
        noLink: true
      })
      if (confirmation.response !== 1) return { copied: false }
    }
    const config = manager.getClientConfig(kind, process.execPath, app.getAppPath(), Boolean(process.defaultApp))
    clipboard.writeText(config)
    return { copied: true }
  })
  ipcMain.handle(MCP_IPC_CHANNELS.syncAuth, async (event, auth: unknown) => {
    assertTrustedSender(event)
    return await manager.syncAuth(validateAuth(auth))
  })
  ipcMain.handle(MCP_IPC_CHANNELS.clearAuth, async (event) => {
    assertTrustedSender(event)
    return await manager.clearAuth()
  })
}
