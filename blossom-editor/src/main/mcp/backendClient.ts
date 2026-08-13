const MAX_RESPONSE_BYTES = 16 * 1024 * 1024
const REQUEST_TIMEOUT_MS = 60_000

export class BlossomApiError extends Error {
  readonly status?: number
  readonly code?: string | number

  constructor(message: string, options?: { status?: number; code?: string | number }) {
    super(message)
    this.name = 'BlossomApiError'
    this.status = options?.status
    this.code = options?.code
  }

  get isUnauthorized(): boolean {
    const code = String(this.code || '')
    return this.status === 401 || code === 'AI-AUTH-INVALID' || code === 'AUTH-40101'
  }
}

interface BlossomResponse<T> {
  code: string | number
  msg?: string
  data: T
}

const isSuccessCode = (code: string | number): boolean => code === 0 || code === '0' || code === 20000 || code === '20000'

export const normalizeBlossomServerUrl = (rawUrl: string): string => {
  const url = new URL(rawUrl)
  if (url.protocol !== 'http:' && url.protocol !== 'https:') throw new Error('Blossom 服务地址只允许 http 或 https')
  const isLoopback = url.hostname === 'localhost' || url.hostname === '127.0.0.1' || url.hostname === '[::1]'
  if (url.protocol === 'http:' && !isLoopback) throw new Error('非本机 Blossom 服务必须使用 https')
  if (url.username || url.password || url.search || url.hash) throw new Error('Blossom 服务地址不能包含凭据、查询参数或片段')
  return url.toString().replace(/\/$/, '')
}

const readLimitedText = async (response: Response): Promise<string> => {
  const declaredLength = Number(response.headers.get('content-length') || 0)
  if (declaredLength > MAX_RESPONSE_BYTES) throw new BlossomApiError('Blossom 服务响应过大', { status: response.status })
  if (!response.body) return ''

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let total = 0
  let text = ''
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    total += value.byteLength
    if (total > MAX_RESPONSE_BYTES) {
      await reader.cancel()
      throw new BlossomApiError('Blossom 服务响应过大', { status: response.status })
    }
    text += decoder.decode(value, { stream: true })
  }
  return text + decoder.decode()
}

export const requestBlossomApi = async <T>(options: {
  serverUrl: string
  token: string
  path: string
  method?: 'GET' | 'POST' | 'PATCH'
  body?: unknown
}): Promise<T> => {
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS)
  try {
    const response = await fetch(`${normalizeBlossomServerUrl(options.serverUrl)}${options.path}`, {
      method: options.method || 'GET',
      redirect: 'error',
      signal: controller.signal,
      headers: {
        Accept: 'application/json',
        Authorization: `Bearer ${options.token}`,
        ...(options.body === undefined ? {} : { 'Content-Type': 'application/json' })
      },
      body: options.body === undefined ? undefined : JSON.stringify(options.body)
    })
    const responseText = await readLimitedText(response)
    let envelope: BlossomResponse<T>
    try {
      envelope = JSON.parse(responseText) as BlossomResponse<T>
    } catch {
      throw new BlossomApiError('Blossom 服务返回了无效响应', { status: response.status })
    }
    if (!response.ok || !isSuccessCode(envelope.code)) {
      throw new BlossomApiError(envelope.msg || `Blossom 请求失败 (${response.status})`, {
        status: response.status,
        code: envelope.code
      })
    }
    return envelope.data
  } catch (error) {
    if (error instanceof BlossomApiError) throw error
    if ((error as Error).name === 'AbortError') throw new BlossomApiError('Blossom 请求超时')
    throw new BlossomApiError('无法连接 Blossom 服务')
  } finally {
    clearTimeout(timeout)
  }
}
