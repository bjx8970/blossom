const blockedTags = new Set([
  'script',
  'style',
  'object',
  'embed',
  'base',
  'meta',
  'link',
  'form',
  'input',
  'button',
  'textarea',
  'select',
  'option',
  'foreignobject',
  'template',
  'noscript',
  'animate',
  'animatemotion',
  'animatetransform',
  'set'
])

const urlAttributes = new Set(['href', 'src', 'poster', 'background', 'action', 'formaction', 'xlink:href'])
const allowedHtmlEvents = new Set(['copyPreCode', 'showArticleReferenceView'])

const isSafeUrl = (value: string, attribute: string, tagName: string): boolean => {
  const compact = value.replace(/[\u0000-\u0020\u007f-\u009f]/g, '').toLowerCase()
  if (compact === '' || compact.startsWith('#') || compact.startsWith('/') || compact.startsWith('./') || compact.startsWith('../')) {
    return true
  }
  if (compact.startsWith('http://') || compact.startsWith('https://')) return true
  if (attribute === 'href' && (compact.startsWith('mailto:') || compact.startsWith('tel:'))) return true
  if (tagName === 'img' && compact.startsWith('blob:')) return true
  if (tagName === 'img' && /^data:image\/(?:png|jpe?g|gif|webp);base64,/i.test(compact)) return true
  return false
}

const sanitizeElement = (element: Element) => {
  const tagName = element.tagName.toLowerCase()
  if (blockedTags.has(tagName)) {
    element.remove()
    return
  }

  if (tagName === 'iframe') {
    const src = element.getAttribute('src') || ''
    if (!/^https:\/\/player\.bilibili\.com\/player\.html(?:\?|$)/i.test(src)) {
      element.remove()
      return
    }
    element.setAttribute('sandbox', 'allow-scripts allow-same-origin allow-presentation')
    element.setAttribute('referrerpolicy', 'no-referrer')
  }

  for (const attribute of Array.from(element.attributes)) {
    const attributeName = attribute.name.toLowerCase()
    if (
      attributeName.startsWith('on') ||
      attributeName === 'srcdoc' ||
      attributeName === 'srcset' ||
      attributeName === 'ping' ||
      attributeName === 'nonce' ||
      attributeName === 'xml:base'
    ) {
      element.removeAttribute(attribute.name)
      continue
    }
    if (urlAttributes.has(attributeName) && !isSafeUrl(attribute.value, attributeName, tagName)) {
      element.removeAttribute(attribute.name)
      continue
    }
    if (
      attributeName === 'style' &&
      /(?:expression\s*\(|url\s*\(|@import|behavior\s*:|-moz-binding|position\s*:\s*(?:fixed|sticky))/i.test(attribute.value)
    ) {
      element.removeAttribute(attribute.name)
    }
  }

  const eventName = element.getAttribute('data-bl-event')
  if (eventName && !allowedHtmlEvents.has(eventName)) {
    element.removeAttribute('data-bl-event')
    element.removeAttribute('data-bl-data')
  }
  if (eventName === 'copyPreCode' && !/^pre-\d+-\d+$/.test(element.getAttribute('data-bl-data') || '')) {
    element.removeAttribute('data-bl-event')
    element.removeAttribute('data-bl-data')
  }
  if (eventName === 'showArticleReferenceView' && !/^\d+$/.test(element.getAttribute('data-bl-data') || '')) {
    element.removeAttribute('data-bl-event')
    element.removeAttribute('data-bl-data')
  }

  if (element.getAttribute('target') === '_blank') {
    element.setAttribute('rel', 'noopener noreferrer')
  }
}

/** 清理已经由图表库写入页面的节点，同时保留库通过 addEventListener 绑定的交互。 */
export const sanitizeArticleElement = (root: Element) => {
  Array.from(root.querySelectorAll('*')).forEach(sanitizeElement)
  sanitizeElement(root)
}

/**
 * 清理 Markdown/服务端返回 HTML 中的可执行内容。
 * 保留 Blossom 预览所需的样式、KaTeX/SVG 和受限的 data 事件标记。
 */
export const sanitizeArticleHtml = (html: string | null | undefined): string => {
  if (!html) return ''
  const template = document.createElement('template')
  template.innerHTML = html
  Array.from(template.content.querySelectorAll('*')).forEach(sanitizeElement)
  return template.innerHTML
}
