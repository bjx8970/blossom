import { writeText } from '@renderer/assets/utils/electron'
import { Ref, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { articleInfoApi } from '@renderer/api/blossom'
import { sanitizeArticleHtml } from './sanitize-html'

type ArticleHtmlEvent = 'copyPreCode' | 'showArticleReferenceView'

const articleViewWidth = 550
const articleViewHeight = 370

export function useArticleHtmlEvent(articleViewRef: Ref<HTMLElement>) {
  let bindCloseTimer: NodeJS.Timeout | undefined
  const articleReferenceView = ref({
    show: false,
    html: '',
    articleId: '0',
    name: '',
    style: {
      top: '0',
      left: '0',
      width: '100px',
      height: '100px'
    }
  })

  const closeView = () => {
    if (articleViewRef.value) {
      articleViewRef.value.removeEventListener('mouseleave', closeView)
    }
    articleReferenceView.value.show = false
  }

  function onHtmlEventDispatch(event: MouseEvent, type: ArticleHtmlEvent, data: string) {
    /*
     复制代码块内容
     */
    if (type === 'copyPreCode') {
      const code = document.getElementById(data)
      if (code) {
        writeText(code.innerText)
      }
      return
    }

    /*
     打开文章预览
     */
    if (type === 'showArticleReferenceView') {
      event.preventDefault()
      const target = event.target as HTMLElement
      const rect = target.getBoundingClientRect()
      let top = rect.top + rect.height + 10
      if (document.body.clientHeight - top < articleViewHeight) {
        top = rect.top - articleViewHeight - 10
      }
      articleReferenceView.value.style = {
        left: rect.left + 'px',
        top: top + 'px',
        width: `${articleViewWidth}px`,
        height: `${articleViewHeight}px`
      }

      articleReferenceView.value.show = true
      articleReferenceView.value.articleId = data
      articleReferenceView.value.html = `<p style="color:var(--bl-text-color-light)">正在加载文章...</p>`

      nextTick(() => {
        bindCloseTimer = setTimeout(() => articleViewRef.value?.addEventListener('mouseleave', closeView), 100)
        articleInfoApi({ id: data, showToc: false, showMarkdown: false, showHtml: true }).then((resp) => {
          articleReferenceView.value.html = sanitizeArticleHtml(resp.data.html)
          articleReferenceView.value.name = resp.data.name
        })
      })
    }
  }

  const handlePreviewClick = (event: MouseEvent) => {
    const target = event.target as HTMLElement | null
    const eventElement = target?.closest<HTMLElement>('[data-bl-event]')
    if (!eventElement || !eventElement.closest('.bl-preview')) return
    const type = eventElement.dataset.blEvent as ArticleHtmlEvent | undefined
    const data = eventElement.dataset.blData
    if (!type || !data || (type !== 'copyPreCode' && type !== 'showArticleReferenceView')) return
    onHtmlEventDispatch(event, type, data)
  }

  onMounted(() => {
    document.addEventListener('click', handlePreviewClick)
  })

  onBeforeUnmount(() => {
    document.removeEventListener('click', handlePreviewClick)
    if (bindCloseTimer) clearTimeout(bindCloseTimer)
    closeView()
  })

  return { articleReferenceView }
}
