import { ElectronAPI } from '@electron-toolkit/preload'
import type { McpCopyConfigKind, McpRendererAuth, McpServiceStatus } from '../main/mcp/types'

interface McpPreloadAPI {
  mcpGetStatus(): Promise<McpServiceStatus>
  mcpSetEnabled(enabled: boolean): Promise<McpServiceStatus>
  mcpSetPort(port: number): Promise<McpServiceStatus>
  mcpRotateToken(): Promise<McpServiceStatus>
  mcpCopyConfig(kind?: McpCopyConfigKind): Promise<{ copied: boolean }>
  mcpSyncAuth(auth: McpRendererAuth): Promise<McpServiceStatus>
  mcpClearAuth(): Promise<McpServiceStatus>
}

declare global {
  interface Window {
    electron: ElectronAPI
    electronAPI: ElectronAPI & McpPreloadAPI
    api: {}
  }
}
