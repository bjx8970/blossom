import { app, safeStorage } from 'electron'
import { randomBytes, randomUUID } from 'node:crypto'
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import type { McpPersistedConfig, McpStorageSecurity } from './types'

const CONFIG_FILE = 'config.bin'

export const createDefaultMcpConfig = (): McpPersistedConfig => ({
  version: 1,
  enabled: false,
  port: 0,
  localBearer: randomBytes(32).toString('base64url'),
  deviceId: randomUUID(),
  deviceCredentials: []
})

const isPersistedConfig = (value: unknown): value is McpPersistedConfig => {
  if (!value || typeof value !== 'object') return false
  const config = value as Partial<McpPersistedConfig>
  return (
    config.version === 1 &&
    typeof config.enabled === 'boolean' &&
    typeof config.port === 'number' &&
    typeof config.localBearer === 'string' &&
    typeof config.deviceId === 'string' &&
    Array.isArray(config.deviceCredentials)
  )
}

export class McpConfigStore {
  private readonly configPath: string

  constructor() {
    this.configPath = join(app.getPath('userData'), 'mcp', CONFIG_FILE)
  }

  getStorageSecurity(): McpStorageSecurity {
    if (!safeStorage.isEncryptionAvailable()) return 'unavailable'
    if (process.platform === 'linux' && safeStorage.getSelectedStorageBackend() === 'basic_text') return 'basic_text'
    return 'secure'
  }

  async load(): Promise<McpPersistedConfig> {
    try {
      const encrypted = await readFile(this.configPath)
      const parsed: unknown = JSON.parse(safeStorage.decryptString(encrypted))
      if (!isPersistedConfig(parsed)) throw new Error('MCP 配置格式无效')
      return parsed
    } catch (error) {
      const fsError = error as NodeJS.ErrnoException
      if (fsError.code === 'ENOENT') return createDefaultMcpConfig()
      throw error
    }
  }

  async save(config: McpPersistedConfig): Promise<void> {
    if (this.getStorageSecurity() !== 'secure') {
      throw new Error('当前系统没有可用的安全凭据存储，无法保存 MCP 配置')
    }
    const encrypted = safeStorage.encryptString(JSON.stringify(config))
    const temporaryPath = `${this.configPath}.tmp`
    await mkdir(dirname(this.configPath), { recursive: true, mode: 0o700 })
    await writeFile(temporaryPath, encrypted, { mode: 0o600 })
    await rename(temporaryPath, this.configPath)
  }
}
