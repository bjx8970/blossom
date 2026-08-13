import { globalCSS as markmapGlobalCss } from 'markmap-view'

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
const markmapSvgTags = new Set(['svg', 'style', 'g', 'path', 'line', 'circle', 'foreignobject'])
const markmapHtmlTags = new Set([
  'div',
  'p',
  'span',
  'a',
  'code',
  'pre',
  'del',
  'em',
  'strong',
  'mark',
  'table',
  'thead',
  'tbody',
  'tr',
  'th',
  'td',
  'img',
  'br',
  'ul',
  'ol',
  'li'
])

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

const sanitizeAttributes = (element: Element): void => {
  const tagName = element.tagName.toLowerCase()
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

const sanitizeElement = (element: Element) => {
  if (blockedTags.has(element.tagName.toLowerCase())) {
    element.remove()
    return
  }
  sanitizeAttributes(element)
}

const hasClass = (element: Element, className: string): boolean =>
  (element.getAttribute('class') || '').split(/\s+/).filter(Boolean).includes(className)

const closestByTagAndClass = (element: Element, tagName: string, className: string): Element | null => {
  let current: Element | null = element
  while (current) {
    if (current.tagName.toLowerCase() === tagName && hasClass(current, className)) return current
    current = current.parentElement
  }
  return null
}

const sanitizeMarkmapStyle = (style: Element, svg: SVGElement): void => {
  // markmap-view creates exactly one direct <style> node. CSS here is generated
  // by the library, never copied from Markdown. Reject anything that could load
  // external content or escape into legacy executable CSS.
  const directStyles = Array.from(svg.children).filter((element) => element.tagName.toLowerCase() === 'style')
  if (style.parentNode !== svg || directStyles.length !== 1) {
    style.remove()
    return
  }
  for (const attribute of Array.from(style.attributes)) style.removeAttribute(attribute.name)
  if ((style.textContent || '') !== markmapGlobalCss) {
    style.remove()
  }
}

const sanitizeMarkmapForeignObject = (foreignObject: Element): void => {
  if (!hasClass(foreignObject, 'markmap-foreign') || !closestByTagAndClass(foreignObject.parentElement || foreignObject, 'g', 'markmap-node')) {
    foreignObject.remove()
    return
  }
  for (const descendant of Array.from(foreignObject.querySelectorAll('*'))) {
    if (!markmapHtmlTags.has(descendant.tagName.toLowerCase())) {
      descendant.remove()
      continue
    }
    sanitizeElement(descendant)
  }
  sanitizeAttributes(foreignObject)
}

/**
 * Clean the SVG produced by markmap-view without deleting its node labels.
 * This is deliberately separate from the article sanitizer: foreignObject and
 * SVG style remain forbidden everywhere except a real `.markmap` SVG tree.
 */
export const sanitizeMarkmapSvg = (svg: SVGElement): void => {
  if (svg.tagName.toLowerCase() !== 'svg' || !hasClass(svg, 'markmap')) {
    svg.remove()
    return
  }
  for (const element of Array.from(svg.querySelectorAll('*'))) {
    const tagName = element.tagName.toLowerCase()
    const foreignObject = closestByTagAndClass(element, 'foreignobject', 'markmap-foreign')
    if (!markmapSvgTags.has(tagName) && !foreignObject) {
      element.remove()
      continue
    }
    if (tagName === 'style') {
      sanitizeMarkmapStyle(element, svg)
    } else if (tagName === 'foreignobject') {
      sanitizeMarkmapForeignObject(element)
    } else if (!foreignObject) {
      sanitizeElement(element)
    }
  }
  sanitizeElement(svg)
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
  const walk = (root: ParentNode): void => {
    for (const element of Array.from(root.children)) {
      if (element.tagName.toLowerCase() === 'svg' && hasClass(element, 'markmap')) {
        sanitizeMarkmapSvg(element as SVGElement)
        continue
      }
      sanitizeElement(element)
      if (element.isConnected || element.parentNode) walk(element)
    }
  }
  walk(template.content)
  return template.innerHTML
}
