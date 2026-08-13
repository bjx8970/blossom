// @ts-nocheck
import { is } from '@electron-toolkit/utils'
import { isElectron } from './util'
import router from '@renderer/router'

export type McpServiceState = 'disabled' | 'starting' | 'waiting-auth' | 'running' | 'stopping' | 'error'

export interface McpServiceStatus {
  enabled: boolean
  running: boolean
  state: McpServiceState
  host: string
  port: number
  endpoint: string
  authenticated: boolean
  userId?: string | number
  username?: string
  scopes: string[]
  lastCallAt?: string
  error?: string
  storageSecurity: 'secure' | 'basic_text' | 'unavailable'
}

export interface McpAuthSnapshot {
  serverUrl: string
  token: string
  userId: string | number
  username: string
}

const unavailableMcpStatus = (): McpServiceStatus => ({
  enabled: false,
  running: false,
  state: 'disabled',
  host: '127.0.0.1',
  port: 0,
  endpoint: '',
  authenticated: false,
  scopes: [],
  error: '当前环境不是 Blossom 桌面客户端',
  storageSecurity: 'unavailable'
})

/** 获取本机 AI 工具接入服务状态。 */
export const mcpGetStatus = async (): Promise<McpServiceStatus> => {
  if (!isElectron()) return unavailableMcpStatus()
  return window.electronAPI.mcpGetStatus()
}

/** 开启或关闭本机 AI 工具接入服务。 */
export const mcpSetEnabled = async (enabled: boolean): Promise<McpServiceStatus> => {
  if (!isElectron()) return unavailableMcpStatus()
  return window.electronAPI.mcpSetEnabled(enabled)
}

/** 修改 AI 工具接入服务监听端口。 */
export const mcpSetPort = async (port: number): Promise<McpServiceStatus> => {
  if (!isElectron()) return unavailableMcpStatus()
  return window.electronAPI.mcpSetPort(port)
}

/** 轮换本机服务凭证。明文凭证不会返回到 renderer。 */
export const mcpRotateToken = async (): Promise<McpServiceStatus> => {
  if (!isElectron()) return unavailableMcpStatus()
  return window.electronAPI.mcpRotateToken()
}

/** 由主进程将连接配置直接写入系统剪贴板。 */
export const mcpCopyConfig = async (kind: 'http' | 'stdio' = 'stdio'): Promise<{ copied: boolean }> => {
  if (!isElectron()) return { copied: false }
  return window.electronAPI.mcpCopyConfig(kind)
}

/** 将当前登录快照同步给主进程，仅通过受限 IPC 传输。 */
export const mcpSyncAuth = async (auth: McpAuthSnapshot): Promise<McpServiceStatus> => {
  if (!isElectron()) return unavailableMcpStatus()
  return window.electronAPI.mcpSyncAuth(auth)
}

/** 清除主进程中的登录快照。 */
export const mcpClearAuth = async (): Promise<McpServiceStatus> => {
  if (!isElectron()) return unavailableMcpStatus()
  return window.electronAPI.mcpClearAuth()
}

/** 打开控制台, 会在打开和关闭之间切换 */
export const openDevTools = () => {
  window.electronAPI.openDevTools()
}
/** 最小化窗口 */
export const windowMin = () => {
  window.electronAPI.windowMin()
}
/** 会在最大化和还原之间切换 */
export const windowMax = () => {
  window.electronAPI.windowMax()
}
/** 隐藏窗口 */
export const windowHide = () => {
  window.electronAPI.windowHide()
}
/** 关闭窗口 */
export const windowClose = () => {
  window.electronAPI.windowClose()
}
/** 最佳窗口 */
export const setBestSize = () => {
  window.electronAPI.setBestSize()
}
/** 设置创建缩放变化  */
export const setZoomLevel = (level: number) => {
  window.electronAPI.setZoomLevel(level)
}
/** 重置窗口缩放 */
export const resetZoomLevel = () => {
  window.electronAPI.resetZoomLevel()
}

/**
 * 设置用户信息
 * @param userinfo 用户信息
 */
export const setUserinfo = (userinfo: any) => {
  if (isElectron()) {
    window.electronAPI.setUserinfo(userinfo)
  }
}

/**
 * 打开文章窗口
 * @param articleName 文章名称
 * @param articleId 文章ID
 */
export const openNewArticleWindow = (articleName: string, articleId: string) => {
  if (isElectron()) {
    window.electronAPI.openNewArticleWindow({ name: articleName, id: articleId })
  } else {
    const href = router.resolve({ name: 'ArticleViewWindow', path: `/articleViewWindow`, query: { articleId: articleId } })
    window.open(href.href, '_blank')
  }
}

/**
 * 打开新图标窗口
 */
export const openNewIconWindow = () => {
  if (isElectron()) {
    window.electronAPI.openNewIconWindow()
  } else {
    const href = router.resolve({ name: 'IconListIndexWindow', path: `/iconListIndexWindow` })
    window.open(href.href, '_blank')
  }
}

/**
 * 打开文章引用窗口
 * @param article 指定文章的引用
 */
export const openNewArticleReferenceWindow = (article?: any) => {
  if (isElectron()) {
    window.electronAPI.openNewArticleReferenceWindow(article)
  } else {
    const href = router.resolve({ name: 'ArticleReferenceWindow', path: `/articleReferenceWindow`, query: { articleId: article?.id } })
    window.open(href.href, '_blank')
  }
}

/**
 * 打开文章编辑历史记录
 */
export const openNewArticleLogWindow = (article: any) => {
  if (isElectron()) {
    window.electronAPI.openNewArticleLogWindow(article)
  } else {
    const href = router.resolve({ name: 'ArticleHistory', path: `/articleHistory`, query: { articleId: article?.id } })
    window.open(href.href, '_blank')
  }
}

/**
 * 使用默认浏览器打开链接
 * @param url 访问链接
 */
export const openExtenal = (url: string): void => {
  if (isElectron()) {
    window.electronAPI.openExtenal(url)
  } else {
    window.open(url)
  }
}

/**
 * 截屏
 */
export const printScreen = (): void => {
  window.electronAPI.printScreen()
}

/**
 * 剪切板, 读取图片, 并转换成 base64 字符串
 * @returns base64 字符串
 */
export const readImageToDataUrl = (): string => {
  return window.electronAPI.readImageToDataUrl('clipboard')
}

/**
 * 剪切板, 读取图片, 并转换成 png 图片 buffer
 * @returns png 图片 buffer
 */
export const readImageToPNG = (): string => {
  return window.electronAPI.readImageToPNG('clipboard')
}

/**
 * 剪切板, 读取图片, 并转换成 jpeg 图片 buffer
 * @returns jpeg 图片 buffer
 */
export const readImageToJPEG = (): string => {
  return window.electronAPI.readImageToJPEG('clipboard')
}

/**
 * 向剪切板写入文本
 * @param text 文本内容
 */
export const writeText = (text: string): void => {
  if (isElectron()) {
    window.electronAPI.writeText(text, 'clipboard')
  } else {
    if (navigator.clipboard && window.isSecureContext) {
      const type = 'text/plain'
      const blob = new Blob([text], { type })
      const data = [new ClipboardItem({ [type]: blob })]
      navigator.clipboard.write(data).then(
        () => {},
        () => {}
      )
    } else {
      let textArea = document.createElement('textarea')
      textArea.value = text
      textArea.style.position = 'absolute'
      textArea.style.opacity = '0'
      textArea.style.left = '-999999px'
      textArea.style.top = '-999999px'
      document.body.appendChild(textArea)
      textArea.focus()
      textArea.select()
      return new Promise<void>((res, rej) => {
        document.execCommand('copy') ? res() : rej()
        textArea.remove()
      })
    }
  }
}

/**
 * 从剪切板读取文本
 * @returns text 文本内容
 */
export const readText = (): string => {
  if (isElectron()) {
    return window.electronAPI.readText()
  }
}

/**
 * 下载
 * @param url 文件路径
 */
export const download = (url: string): void => {
  if (isElectron()) {
    window.electronAPI.download(url)
  } else {
    window.open(url)
  }
}
