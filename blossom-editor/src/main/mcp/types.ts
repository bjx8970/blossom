export const MCP_HOST = '127.0.0.1' as const
export const MCP_PATH = '/mcp' as const
export const MCP_PROTOCOL_VERSION = '2025-06-18' as const

export type McpStorageSecurity = 'secure' | 'basic_text' | 'unavailable'
export type McpServiceState = 'disabled' | 'starting' | 'waiting-auth' | 'running' | 'stopping' | 'error'

export interface McpServiceStatus {
  enabled: boolean
  running: boolean
  state: McpServiceState
  host: typeof MCP_HOST
  port: number
  endpoint: string
  authenticated: boolean
  scopes: string[]
  userId?: string
  username?: string
  lastCallAt?: string
  error?: string
  storageSecurity: McpStorageSecurity
}

export interface McpRendererAuth {
  serverUrl: string
  token: string
  userId: string | number
  username: string
}

export interface McpBackendAuth {
  serverUrl: string
  token: string
  userId: string
  username: string
}

export interface McpDeviceCredential extends McpBackendAuth {
  tokenId: string
  expireTime?: string
  pendingRevoke?: boolean
}

export interface McpPersistedConfig {
  version: 1
  enabled: boolean
  port: number
  localBearer: string
  deviceId: string
  deviceCredentials: McpDeviceCredential[]
}

export type McpCopyConfigKind = 'http' | 'stdio'

export type MainToMcpUtilityMessage =
  | {
      type: 'start'
      port: number
      localBearer: string
      backendAuth?: McpBackendAuth
    }
  | { type: 'update-local-bearer'; localBearer: string }
  | { type: 'update-backend-auth'; backendAuth: McpBackendAuth }
  | { type: 'clear-backend-auth'; requestId: string }
  | { type: 'shutdown' }

export type McpUtilityToMainMessage =
  | { type: 'ready'; port: number }
  | { type: 'stopped' }
  | { type: 'last-call'; at: string }
  | { type: 'backend-auth-invalid' }
  | { type: 'backend-auth-cleared'; requestId: string }
  | { type: 'error'; code: string; message: string }
