import { JSDOM } from 'jsdom'

const assert = (condition: unknown, message: string): asserts condition => {
  if (!condition) throw new Error(message)
}

const run = async (): Promise<void> => {
  const dom = new JSDOM('<!doctype html><html><body></body></html>', {
    pretendToBeVisual: true,
    url: 'https://localhost/'
  })
  const { window } = dom
  Object.assign(globalThis, {
    window,
    document: window.document,
    Element: window.Element,
    HTMLElement: window.HTMLElement,
    SVGElement: window.SVGElement,
    Node: window.Node,
    Image: window.Image,
    Event: window.Event,
    MouseEvent: window.MouseEvent,
    WheelEvent: window.WheelEvent,
    requestAnimationFrame: window.requestAnimationFrame.bind(window),
    cancelAnimationFrame: window.cancelAnimationFrame.bind(window),
    getComputedStyle: window.getComputedStyle.bind(window)
  })
  Object.defineProperty(globalThis, 'navigator', { value: window.navigator, configurable: true })

  const [{ Transformer }, { Markmap, deriveOptions, globalCSS }, { sanitizeArticleHtml, sanitizeMarkmapSvg }] = await Promise.all([
    import('markmap-lib'),
    import('markmap-view'),
    import('../src/renderer/src/views/article/scripts/sanitize-html')
  ])

  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg')
  svg.setAttribute('width', '800')
  svg.setAttribute('height', '600')
  document.body.append(svg)

  const transformer = new Transformer()
  const { root } = transformer.transform(`# Blossom\n\n## MCP\n\n- **read** notes\n- edit [safely](https://example.com)`)
  const markmapOptions = {
    ...deriveOptions({ duration: 0 }),
    autoFit: false,
    pan: false,
    zoom: false
  }
  const markmap = Markmap.create(svg, markmapOptions, root)

  assert(svg.matches('svg.markmap'), 'Markmap.create() did not mark the SVG root')
  assert(svg.querySelector('style')?.textContent?.includes('.markmap-foreign'), 'real Markmap style was not generated')
  assert(svg.querySelectorAll('foreignObject.markmap-foreign').length >= 2, 'real Markmap node labels were not generated')

  sanitizeMarkmapSvg(svg)
  assert(svg.querySelector('style')?.textContent?.includes('.markmap-foreign'), 'Markmap style was removed by the SVG sanitizer')
  assert(svg.querySelectorAll('foreignObject.markmap-foreign').length >= 2, 'Markmap node labels were removed by the SVG sanitizer')
  assert(svg.textContent?.includes('Blossom') && svg.textContent.includes('MCP'), 'Markmap label text was removed by the SVG sanitizer')

  const serialized = sanitizeArticleHtml(svg.outerHTML)
  assert(
    /<style(?:\s|>)[\s\S]*?\.markmap-foreign/i.test(serialized),
    `Markmap style was removed while serializing article HTML: ${serialized.slice(0, 1000)}`
  )
  assert(
    (serialized.match(/<foreignObject\b[^>]*class="[^"]*markmap-foreign/g) || []).length >= 2,
    'Markmap node labels were removed while serializing article HTML'
  )

  const genericSvg = sanitizeArticleHtml('<svg><style>.stolen{display:none}</style><foreignObject><div>unsafe</div></foreignObject></svg>')
  assert(!/style|foreignObject/i.test(genericSvg), 'generic article SVG unexpectedly retained Markmap-only elements')

  const hostileMarkmap = sanitizeArticleHtml(
    `<svg class="markmap"><style>${globalCSS}.article-body{display:none}</style>` +
      '<g class="markmap-node"><foreignObject class="markmap-foreign"><div onclick="alert(1)">safe text<script>alert(1)</script></div></foreignObject></g>' +
      '</svg>'
  )
  assert(!/<style/i.test(hostileMarkmap), 'unsafe Markmap CSS was retained')
  assert(!/onclick|<script/i.test(hostileMarkmap), 'executable Markmap label content was retained')
  assert(hostileMarkmap.includes('safe text'), 'safe Markmap label content was removed')

  markmap.destroy()
  window.close()
}

run()
  .then(() => {
    console.info('MARKMAP_SANITIZE_TEST_OK')
    process.exit(0)
  })
  .catch((error) => {
    console.error('MARKMAP_SANITIZE_TEST_FAILED', error instanceof Error ? error.stack : error)
    process.exit(1)
  })
