import { utilityProcess, type UtilityProcess } from 'electron'
import { createHash, randomBytes } from 'node:crypto'
import { createServer } from 'node:net'
import { join } from 'node:path'
import { BlossomApiError, normalizeBlossomServerUrl, requestBlossomApi } from './backendClient'
import { createDefaultMcpConfig, McpConfigStore } from './configStore'
import { DeviceTokenRotationTimer } from './deviceTokenRotationTimer'
import {
  MCP_HOST,
  MCP_PATH,
  type MainToMcpUtilityMessage,
  type McpBackendAuth,
  type McpCopyConfigKind,
  type McpDeviceCredential,
  type McpPersistedConfig,
  type McpRendererAuth,
  type McpServiceState,
  type McpServiceStatus,
  type McpStorageSecurity,
  type McpUtilityToMainMessage
} from './types'

const DEVICE_SCOPES = ['articles:read', 'articles:write']
const PROACTIVE_ROTATE_RETRY_MS = 60 * 60 * 1_000
const RESTART_DELAYS_MS = [1_000, 5_000, 15_000]
const RESTART_WINDOW_MS = 60_000
const UTILITY_AUTH_CLEAR_TIMEOUT_MS = 2_000

interface RuntimeLogin extends Omit<McpRendererAuth, 'userId'> {
  userId: string
}

interface DeviceTokenResponse {
  id?: string | number
  tokenId?: string | number
  deviceTokenId?: string | number
  token?: string
  accessToken?: string
  deviceToken?: string | DeviceTokenResponse
  expireTime?: string | number
}

const findAvailablePort = async (): Promise<number> =>
  await new Promise<number>((resolve, reject) => {
    const probe = createServer()
    probe.once('error', reject)
    probe.listen(0, MCP_HOST, () => {
      const address = probe.address()
      const port = typeof address === 'object' && address ? address.port : 0
      probe.close((error) => (error ? reject(error) : resolve(port)))
    })
  })

const delay = (milliseconds: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, milliseconds))

const getDeviceTokenFields = (response: DeviceTokenResponse): { tokenId: string; token: string; expireTime?: string } => {
  const nested = typeof response.deviceToken === 'object' ? response.deviceToken : undefined
  const tokenId = response.id ?? response.tokenId ?? response.deviceTokenId ?? nested?.id ?? nested?.tokenId ?? nested?.deviceTokenId
  const token =
    response.token ??
    response.accessToken ??
    (typeof response.deviceToken === 'string' ? response.deviceToken : undefined) ??
    nested?.token ??
    nested?.accessToken
  if (tokenId === undefined || !token) throw new BlossomApiError('Blossom 未返回有效的设备令牌')
  const rawExpireTime = response.expireTime ?? nested?.expireTime
  let expireTime: string | undefined
  if (rawExpireTime !== undefined) {
    const numeric = typeof rawExpireTime === 'number' ? rawExpireTime : Number(rawExpireTime)
    const date = Number.isFinite(numeric) ? new Date(numeric < 10_000_000_000 ? numeric * 1_000 : numeric) : new Date(String(rawExpireTime))
    if (Number.isFinite(date.getTime())) expireTime = date.toISOString()
  }
  return { tokenId: String(tokenId), token, expireTime }
}

const accountDeviceId = (baseDeviceId: string, serverUrl: string, userId: string): string => {
  const accountHash = createHash('sha256').update(`${serverUrl}:${userId}`).digest('hex').slice(0, 16)
  return `${baseDeviceId}_${accountHash}`
}

export class McpServiceManager {
  private readonly configStore = new McpConfigStore()
  private config: McpPersistedConfig = createDefaultMcpConfig()
  private storageSecurity: McpStorageSecurity = 'unavailable'
  private state: McpServiceState = 'disabled'
  private error?: string
  private lastCallAt?: string
  private child?: UtilityProcess
  private runtimeLogin?: RuntimeLogin
  private backendAuth?: McpBackendAuth
  private intentionallyStopping = false
  private restartBlocked = false
  private restartTimestamps: number[] = []
  private restartTimer?: NodeJS.Timeout
  private readonly deviceRotationTimer = new DeviceTokenRotationTimer()
  private readonly utilityAuthClearWaiters = new Map<string, { resolve: () => void; timeout: NodeJS.Timeout; child: UtilityProcess }>()
  private authGeneration = 0
  private refreshingDeviceToken?: Promise<void>
  private proactivelyRotatingDeviceToken?: Promise<void>

  async initialize(): Promise<void> {
    this.storageSecurity = this.configStore.getStorageSecurity()
    try {
      this.config = await this.configStore.load()
      if (this.config.port === 0) {
        this.config.port = await findAvailablePort()
        if (this.storageSecurity === 'secure') await this.configStore.save(this.config)
      }
    } catch (error) {
      this.config = createDefaultMcpConfig()
      this.config.port = await findAvailablePort()
      this.state = 'error'
      this.error = error instanceof Error ? error.message : '无法读取 MCP 配置'
      return
    }

    if (this.storageSecurity !== 'secure') {
      this.state = this.config.enabled ? 'error' : 'disabled'
      if (this.config.enabled) this.error = '当前 Linux 凭据后端为 basic_text 或安全存储不可用，MCP 已阻止启动'
      return
    }
    if (this.config.enabled) this.state = 'waiting-auth'
  }

  getStatus(): McpServiceStatus {
    return {
      enabled: this.config.enabled,
      running: this.child !== undefined && (this.state === 'running' || this.state === 'waiting-auth'),
      state: this.state,
      host: MCP_HOST,
      port: this.config.port,
      endpoint: `http://${MCP_HOST}:${this.config.port}${MCP_PATH}`,
      authenticated: this.backendAuth !== undefined,
      scopes: this.backendAuth ? [...DEVICE_SCOPES] : [],
      userId: this.runtimeLogin?.userId,
      username: this.runtimeLogin?.username,
      lastCallAt: this.lastCallAt,
      error: this.error,
      storageSecurity: this.storageSecurity
    }
  }

  async setEnabled(enabled: boolean): Promise<McpServiceStatus> {
    this.assertSecureStorage()
    if (this.config.enabled === enabled) return this.getStatus()
    this.config.enabled = enabled
    await this.configStore.save(this.config)
    this.error = undefined
    if (!enabled) {
      this.authGeneration += 1
      this.clearDeviceRotationTimer()
      this.backendAuth = undefined
      await this.clearUtilityBackendAuth()
      await this.stopUtility()
      await this.revokeCurrentCredential()
      this.state = 'disabled'
      return this.getStatus()
    }

    if (this.runtimeLogin) {
      await this.ensureBackendAuth().catch((error) => {
        this.error = error instanceof Error ? error.message : '无法签发 Blossom 设备令牌'
      })
    }
    if (this.backendAuth) await this.startUtility()
    else this.state = 'waiting-auth'
    return this.getStatus()
  }

  async setPort(port: number): Promise<McpServiceStatus> {
    this.assertSecureStorage()
    if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('MCP 端口必须是 1024 到 65535 之间的整数')
    if (port === this.config.port) return this.getStatus()
    this.config.port = port
    await this.configStore.save(this.config)
    if (this.config.enabled && this.backendAuth) {
      await this.stopUtility()
      await this.startUtility()
    }
    return this.getStatus()
  }

  async rotateLocalBearer(): Promise<McpServiceStatus> {
    this.assertSecureStorage()
    this.config.localBearer = randomBytes(32).toString('base64url')
    await this.configStore.save(this.config)
    this.sendToUtility({ type: 'update-local-bearer', localBearer: this.config.localBearer })
    return this.getStatus()
  }

  async syncAuth(auth: McpRendererAuth): Promise<McpServiceStatus> {
    const normalized: RuntimeLogin = {
      serverUrl: normalizeBlossomServerUrl(auth.serverUrl),
      token: auth.token,
      userId: String(auth.userId),
      username: auth.username
    }
    if (!normalized.token || !normalized.userId || !normalized.username) throw new Error('MCP 登录上下文不完整')

    const identityChanged =
      this.runtimeLogin !== undefined && (this.runtimeLogin.serverUrl !== normalized.serverUrl || this.runtimeLogin.userId !== normalized.userId)
    if (identityChanged) {
      // Invalidate every in-flight issue/rotation before exposing the next
      // account. Refreshing the login token for the same identity is safe and
      // must not race a second device-token issue against the first one.
      this.authGeneration += 1
      this.clearDeviceRotationTimer()
      this.runtimeLogin = undefined
      this.backendAuth = undefined
      await this.clearUtilityBackendAuth()
      await this.stopUtility()
    }
    this.runtimeLogin = normalized
    this.error = undefined
    if (!this.config.enabled) {
      this.clearDeviceRotationTimer()
      await this.revokePendingCredentialForRuntime().catch((error) => {
        this.error = error instanceof Error ? `设备令牌待撤销：${error.message}` : '设备令牌待撤销'
      })
      this.refreshStateAfterAuthChange()
      return this.getStatus()
    }
    if (this.config.enabled) {
      try {
        await this.ensureBackendAuth()
        await this.startUtility()
      } catch (error) {
        this.backendAuth = undefined
        await this.clearUtilityBackendAuth()
        await this.stopUtility()
        this.error = error instanceof Error ? error.message : '无法签发 Blossom 设备令牌'
      }
    }
    this.refreshStateAfterAuthChange()
    return this.getStatus()
  }

  async clearAuth(): Promise<McpServiceStatus> {
    this.authGeneration += 1
    this.clearDeviceRotationTimer()
    this.runtimeLogin = undefined
    this.backendAuth = undefined
    await this.clearUtilityBackendAuth()
    await this.stopUtility()
    this.error = undefined
    this.refreshStateAfterAuthChange()
    return this.getStatus()
  }

  getClientConfig(kind: McpCopyConfigKind, executablePath: string, applicationPath: string, defaultApp: boolean): string {
    if (kind === 'http') {
      return JSON.stringify(
        {
          mcpServers: {
            blossom: {
              type: 'streamable-http',
              url: this.getStatus().endpoint,
              headers: { Authorization: `Bearer ${this.config.localBearer}` }
            }
          }
        },
        null,
        2
      )
    }
    return JSON.stringify(
      {
        mcpServers: {
          blossom: {
            command: executablePath,
            args: [...(defaultApp ? [applicationPath] : []), '--mcp-stdio']
          }
        }
      },
      null,
      2
    )
  }

  async stopForQuit(): Promise<void> {
    if (this.restartTimer) clearTimeout(this.restartTimer)
    this.authGeneration += 1
    this.clearDeviceRotationTimer()
    await this.stopUtility()
  }

  private assertSecureStorage(): void {
    if (this.storageSecurity !== 'secure') {
      throw new Error('安全凭据存储不可用；Linux basic_text 模式下禁止启用 MCP')
    }
  }

  private async ensureBackendAuth(): Promise<void> {
    const generation = this.authGeneration
    const login = this.runtimeLogin
    if (!login) return
    const existing = this.config.deviceCredentials.find(
      (credential) => credential.serverUrl === login.serverUrl && credential.userId === login.userId
    )
    if (existing?.pendingRevoke) {
      await this.revokeCredential(existing)
      if (!this.isCurrentAuth(generation, login)) return
      const index = this.config.deviceCredentials.indexOf(existing)
      if (index >= 0) this.config.deviceCredentials.splice(index, 1)
      await this.configStore.save(this.config)
      if (!this.isCurrentAuth(generation, login)) return
    } else if (existing) {
      if (!this.isCurrentAuth(generation, login)) return
      const previousBackendAuth = this.backendAuth
        ? { serverUrl: this.backendAuth.serverUrl, userId: this.backendAuth.userId, token: this.backendAuth.token }
        : undefined
      existing.username = login.username
      const nextBackendAuth: McpBackendAuth = {
        serverUrl: existing.serverUrl,
        token: existing.token,
        userId: existing.userId,
        username: existing.username
      }
      this.backendAuth = nextBackendAuth
      if (
        !previousBackendAuth ||
        previousBackendAuth.serverUrl !== nextBackendAuth.serverUrl ||
        previousBackendAuth.userId !== nextBackendAuth.userId ||
        previousBackendAuth.token !== nextBackendAuth.token
      ) {
        this.sendToUtility({ type: 'update-backend-auth', backendAuth: nextBackendAuth })
      }
      this.scheduleDeviceRotation(existing)
      return
    }

    const response = await requestBlossomApi<DeviceTokenResponse>({
      serverUrl: login.serverUrl,
      token: login.token,
      path: '/api/ai/manage/v1/device-tokens',
      method: 'POST',
      body: {
        deviceId: accountDeviceId(this.config.deviceId, login.serverUrl, login.userId),
        deviceName: `Blossom Desktop (${process.platform})`,
        scopes: DEVICE_SCOPES
      }
    })
    const fields = getDeviceTokenFields(response)
    if (!this.isCurrentAuth(generation, login)) {
      // The user logged out or switched account while issue was in flight.
      // Best-effort revoke avoids leaving a credential the desktop no longer owns.
      await requestBlossomApi<unknown>({
        serverUrl: login.serverUrl,
        token: login.token,
        path: `/api/ai/manage/v1/device-tokens/${encodeURIComponent(fields.tokenId)}/revoke`,
        method: 'POST',
        body: {}
      }).catch(() => undefined)
      return
    }
    const credential: McpDeviceCredential = {
      tokenId: fields.tokenId,
      token: fields.token,
      expireTime: fields.expireTime,
      serverUrl: login.serverUrl,
      userId: login.userId,
      username: login.username
    }
    this.config.deviceCredentials.push(credential)
    await this.configStore.save(this.config)
    if (!this.isCurrentAuth(generation, login)) return
    this.backendAuth = credential
    this.sendToUtility({ type: 'update-backend-auth', backendAuth: credential })
    this.scheduleDeviceRotation(credential)
  }

  private async refreshDeviceToken(): Promise<void> {
    if (this.refreshingDeviceToken) return this.refreshingDeviceToken
    if (this.proactivelyRotatingDeviceToken) {
      await this.proactivelyRotatingDeviceToken
      if (!this.error && this.backendAuth) return
    }
    this.clearDeviceRotationTimer()
    const generation = this.authGeneration
    const login = this.runtimeLogin
    this.refreshingDeviceToken = (async () => {
      if (!login) {
        this.backendAuth = undefined
        await this.clearUtilityBackendAuth()
        this.refreshStateAfterAuthChange()
        return
      }
      const credential = this.config.deviceCredentials.find(
        (item) => item.serverUrl === login.serverUrl && item.userId === login.userId
      )
      try {
        if (!credential) {
          await this.ensureBackendAuth()
        } else {
          if (!(await this.rotateCredential(credential))) return
          if (!this.isCurrentAuth(generation, login)) return
          this.backendAuth = credential
          this.sendToUtility({ type: 'update-backend-auth', backendAuth: credential })
          this.scheduleDeviceRotation(credential)
        }
        if (!this.isCurrentAuth(generation, login)) return
        this.error = undefined
      } catch (error) {
        if (!this.isCurrentAuth(generation, login)) return
        this.backendAuth = undefined
        await this.clearUtilityBackendAuth()
        await this.stopUtility()
        this.error = error instanceof Error ? error.message : 'Blossom 设备令牌已失效'
      } finally {
        if (this.isCurrentAuth(generation, login)) this.refreshStateAfterAuthChange()
      }
    })().finally(() => {
      this.refreshingDeviceToken = undefined
    })
    return this.refreshingDeviceToken
  }

  private async startUtility(): Promise<void> {
    if (!this.config.enabled || !this.backendAuth || this.child) return
    this.intentionallyStopping = false
    this.restartBlocked = false
    this.state = 'starting'
    const child = utilityProcess.fork(join(__dirname, 'mcpUtility.js'), [], {
      serviceName: 'Blossom MCP Service',
      stdio: 'ignore'
    })
    this.child = child
    child.once('spawn', () => {
      if (this.child !== child) return
      this.sendToUtility({
        type: 'start',
        port: this.config.port,
        localBearer: this.config.localBearer,
        backendAuth: this.backendAuth
      })
    })
    child.on('message', (message) => this.handleUtilityMessage(message as McpUtilityToMainMessage))
    child.once('exit', () => this.handleUtilityExit(child))
  }

  private async stopUtility(): Promise<void> {
    if (this.restartTimer) {
      clearTimeout(this.restartTimer)
      this.restartTimer = undefined
    }
    const child = this.child
    if (!child) return
    this.intentionallyStopping = true
    this.state = 'stopping'
    const exited = new Promise<void>((resolve) => child.once('exit', () => resolve()))
    child.postMessage({ type: 'shutdown' } satisfies MainToMcpUtilityMessage)
    await Promise.race([exited, delay(2_000)])
    if (this.child === child) child.kill()
    if (this.child === child) this.child = undefined
    this.resolveUtilityAuthClearWaiters(child)
    this.intentionallyStopping = false
  }

  private async clearUtilityBackendAuth(): Promise<void> {
    const child = this.child
    if (!child) return
    const requestId = randomBytes(16).toString('hex')
    await new Promise<void>((resolve) => {
      const finish = (): void => {
        const waiter = this.utilityAuthClearWaiters.get(requestId)
        if (!waiter) return
        clearTimeout(waiter.timeout)
        this.utilityAuthClearWaiters.delete(requestId)
        resolve()
      }
      const timeout = setTimeout(finish, UTILITY_AUTH_CLEAR_TIMEOUT_MS)
      this.utilityAuthClearWaiters.set(requestId, { resolve: finish, timeout, child })
      child.postMessage({ type: 'clear-backend-auth', requestId } satisfies MainToMcpUtilityMessage)
    })
  }

  private resolveUtilityAuthClearWaiters(child: UtilityProcess): void {
    for (const waiter of [...this.utilityAuthClearWaiters.values()]) {
      if (waiter.child === child) waiter.resolve()
    }
  }

  private sendToUtility(message: MainToMcpUtilityMessage): void {
    this.child?.postMessage(message)
  }

  private handleUtilityMessage(message: McpUtilityToMainMessage): void {
    if (message.type === 'ready') {
      this.state = this.backendAuth ? 'running' : 'waiting-auth'
      this.error = undefined
    } else if (message.type === 'last-call') {
      this.lastCallAt = message.at
    } else if (message.type === 'backend-auth-invalid') {
      void this.refreshDeviceToken()
    } else if (message.type === 'backend-auth-cleared') {
      this.utilityAuthClearWaiters.get(message.requestId)?.resolve()
    } else if (message.type === 'error') {
      this.state = 'error'
      this.error = message.message
      if (message.code === 'EADDRINUSE' || message.code === 'EACCES') this.restartBlocked = true
    }
  }

  private handleUtilityExit(child: UtilityProcess): void {
    if (this.child !== child) return
    this.child = undefined
    this.resolveUtilityAuthClearWaiters(child)
    if (this.intentionallyStopping || !this.config.enabled || !this.backendAuth) return
    if (this.restartBlocked) {
      this.state = 'error'
      return
    }
    const now = Date.now()
    this.restartTimestamps = this.restartTimestamps.filter((timestamp) => now - timestamp < RESTART_WINDOW_MS)
    if (this.restartTimestamps.length >= RESTART_DELAYS_MS.length) {
      this.state = 'error'
      this.error = 'MCP 服务连续崩溃，已停止自动重启'
      return
    }
    const retryIndex = this.restartTimestamps.length
    this.restartTimestamps.push(now)
    this.state = 'starting'
    this.restartTimer = setTimeout(() => {
      this.restartTimer = undefined
      void this.startUtility()
    }, RESTART_DELAYS_MS[retryIndex])
  }

  private refreshStateAfterAuthChange(): void {
    if (!this.config.enabled) this.state = 'disabled'
    else if (!this.runtimeLogin || !this.backendAuth) this.state = this.error ? 'error' : 'waiting-auth'
    else if (!this.child) this.state = this.error ? 'error' : 'starting'
    else this.state = this.backendAuth ? 'running' : 'waiting-auth'
  }

  private clearDeviceRotationTimer(): void {
    this.deviceRotationTimer.clear()
  }

  private isCurrentAuth(generation: number, login: RuntimeLogin): boolean {
    return (
      generation === this.authGeneration &&
      this.runtimeLogin?.serverUrl === login.serverUrl &&
      this.runtimeLogin.userId === login.userId &&
      this.runtimeLogin.userId === login.userId
    )
  }

  private scheduleDeviceRotation(credential: McpDeviceCredential, minimumDelayMs = 0): void {
    this.clearDeviceRotationTimer()
    if (
      !this.config.enabled ||
      credential.pendingRevoke ||
      !credential.expireTime ||
      !this.runtimeLogin ||
      !this.backendAuth ||
      credential.serverUrl !== this.runtimeLogin.serverUrl ||
      credential.userId !== this.runtimeLogin.userId ||
      credential.token !== this.backendAuth.token
    ) {
      return
    }

    const identity = {
      serverUrl: credential.serverUrl,
      userId: credential.userId,
      tokenId: credential.tokenId
    }
    this.deviceRotationTimer.schedule(
      credential.expireTime,
      () => {
        const current = this.findActiveCredential(identity)
        if (!current) return
        void this.rotateDeviceTokenProactively(identity)
      },
      minimumDelayMs
    )
  }

  private findActiveCredential(identity: { serverUrl: string; userId: string; tokenId: string }): McpDeviceCredential | undefined {
    if (
      !this.config.enabled ||
      !this.runtimeLogin ||
      !this.backendAuth ||
      this.runtimeLogin.serverUrl !== identity.serverUrl ||
      this.runtimeLogin.userId !== identity.userId
    ) {
      return undefined
    }
    const credential = this.config.deviceCredentials.find(
      (item) => item.serverUrl === identity.serverUrl && item.userId === identity.userId && item.tokenId === identity.tokenId
    )
    if (!credential || credential.pendingRevoke || credential.token !== this.backendAuth.token) return undefined
    return credential
  }

  private async rotateDeviceTokenProactively(identity: { serverUrl: string; userId: string; tokenId: string }): Promise<void> {
    if (this.refreshingDeviceToken) {
      return this.refreshingDeviceToken.finally(() => {
        const current = this.findActiveCredential(identity)
        if (current) this.scheduleDeviceRotation(current)
      })
    }
    if (this.proactivelyRotatingDeviceToken) {
      return this.proactivelyRotatingDeviceToken.finally(() => {
        const current = this.findActiveCredential(identity)
        if (current) this.scheduleDeviceRotation(current)
      })
    }
    this.proactivelyRotatingDeviceToken = (async () => {
      const credential = this.findActiveCredential(identity)
      if (!credential) return
      try {
        if (!(await this.rotateCredential(credential))) return
        const stillActive =
          this.config.enabled &&
          this.runtimeLogin?.serverUrl === credential.serverUrl &&
          this.runtimeLogin.userId === credential.userId &&
          this.config.deviceCredentials.includes(credential)
        if (!stillActive) return
        this.backendAuth = credential
        this.sendToUtility({ type: 'update-backend-auth', backendAuth: credential })
        this.error = undefined
        // A healthy rotate returns a fresh 90-day expiry. The minimum prevents
        // a malformed near-expiry response from creating a tight rotate loop.
        this.scheduleDeviceRotation(credential, PROACTIVE_ROTATE_RETRY_MS)
      } catch (error) {
        const current = this.findActiveCredential(identity)
        if (!current) return
        this.error = error instanceof Error ? `设备令牌自动轮换失败：${error.message}` : '设备令牌自动轮换失败'
        // Keep the still-valid credential serving requests and retry while the
        // user remains logged in. A later backend 401 uses the strict refresh
        // path, which stops the service if it cannot obtain a valid token.
        this.scheduleDeviceRotation(current, PROACTIVE_ROTATE_RETRY_MS)
      }
    })().finally(() => {
      this.proactivelyRotatingDeviceToken = undefined
    })
    return this.proactivelyRotatingDeviceToken
  }

  private async rotateCredential(credential: McpDeviceCredential): Promise<boolean> {
    const generation = this.authGeneration
    const login = this.runtimeLogin
    if (!login || credential.serverUrl !== login.serverUrl || credential.userId !== login.userId) {
      throw new BlossomApiError('桌面端登录已失效，无法轮换设备令牌')
    }
    const response = await requestBlossomApi<DeviceTokenResponse>({
      serverUrl: login.serverUrl,
      token: login.token,
      path: `/api/ai/manage/v1/device-tokens/${encodeURIComponent(credential.tokenId)}/rotate`,
      method: 'POST',
      body: { scopes: DEVICE_SCOPES }
    })
    const fields = getDeviceTokenFields(response)
    if (!this.isCurrentAuth(generation, login)) return false
    credential.tokenId = fields.tokenId
    credential.token = fields.token
    credential.expireTime = fields.expireTime
    credential.pendingRevoke = false
    credential.username = login.username
    await this.configStore.save(this.config)
    return this.isCurrentAuth(generation, login)
  }

  private async revokeCredential(credential: McpDeviceCredential): Promise<void> {
    if (!this.runtimeLogin) throw new BlossomApiError('桌面端登录已失效，无法撤销设备令牌')
    await requestBlossomApi<unknown>({
      serverUrl: this.runtimeLogin.serverUrl,
      token: this.runtimeLogin.token,
      path: `/api/ai/manage/v1/device-tokens/${encodeURIComponent(credential.tokenId)}/revoke`,
      method: 'POST',
      body: {}
    })
  }

  private async revokeCurrentCredential(): Promise<void> {
    if (!this.runtimeLogin) {
      if (this.config.deviceCredentials.length > 0) {
        this.config.deviceCredentials.forEach((credential) => (credential.pendingRevoke = true))
        await this.configStore.save(this.config)
        this.error = 'MCP 已停用；设备令牌将在对应账号下次登录时撤销'
      }
      return
    }
    const credential = this.config.deviceCredentials.find(
      (item) => item.serverUrl === this.runtimeLogin?.serverUrl && item.userId === this.runtimeLogin.userId
    )
    if (!credential) {
      this.config.deviceCredentials.forEach((item) => (item.pendingRevoke = true))
      await this.configStore.save(this.config)
      if (this.config.deviceCredentials.length > 0) this.error = '其他账号的设备令牌将在对应账号下次登录时撤销'
      return
    }
    try {
      await this.revokeCredential(credential)
      this.config.deviceCredentials = this.config.deviceCredentials.filter((item) => item !== credential)
      this.config.deviceCredentials.forEach((item) => (item.pendingRevoke = true))
      if (this.backendAuth?.token === credential.token) this.backendAuth = undefined
      await this.configStore.save(this.config)
    } catch (error) {
      this.config.deviceCredentials.forEach((item) => (item.pendingRevoke = true))
      await this.configStore.save(this.config)
      this.error = error instanceof Error ? `MCP 已停用，但设备令牌待撤销：${error.message}` : 'MCP 已停用，但设备令牌待撤销'
    }
  }

  private async revokePendingCredentialForRuntime(): Promise<void> {
    if (!this.runtimeLogin) return
    const credential = this.config.deviceCredentials.find(
      (item) => item.pendingRevoke && item.serverUrl === this.runtimeLogin?.serverUrl && item.userId === this.runtimeLogin.userId
    )
    if (!credential) return
    await this.revokeCredential(credential)
    this.config.deviceCredentials = this.config.deviceCredentials.filter((item) => item !== credential)
    await this.configStore.save(this.config)
  }
}
