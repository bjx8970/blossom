package com.blossom.backend.server.ai;

import com.blossom.backend.server.article.reference.ArticleReferenceEnum;
import com.blossom.backend.server.article.reference.pojo.ArticleReferenceReq;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiMarkdownServiceTest {

    private final AiMarkdownService service = new AiMarkdownService();

    @Test
    void derivesSafeHtmlTocAndReferencesFromUntrustedMarkdown() {
        String markdown = "# 标题\n"
                + "<script>alert('xss')</script>\n"
                + "[危险](javascript:alert(1))\n"
                + "[官网](https://example.com/docs)\n"
                + "![图片](https://example.com/image.png)";

        AiMarkdownService.Derived result = service.derive(markdown);

        assertFalse(result.getHtml().contains("<script"));
        assertFalse(result.getHtml().contains("href=\"javascript:"));
        assertTrue(result.getHtml().contains("&lt;script&gt;"));
        assertTrue(result.getHtml().contains("href=\"https://example.com/docs\""));
        assertTrue(result.getToc().contains("标题"));

        List<ArticleReferenceReq> references = result.getReferences();
        assertEquals(2, references.size());
        assertEquals(ArticleReferenceEnum.OUTSIDE.getType(), references.get(0).getType());
        assertEquals(ArticleReferenceEnum.FILE.getType(), references.get(1).getType());
    }

    @Test
    void supportsGfmExtensionsAndKeepsHeadingIdsAndDoubleLinkReferences() {
        String markdown = "## 功能清单\n\n"
                + "| 功能 | 状态 |\n| --- | --- |\n| 表格 | ~~旧~~ 新 |\n\n"
                + "- [x] 已完成\n\n"
                + "https://example.org/auto\n\n"
                + "[关联笔记](https://example.org/articles/123 \"##123##\")";

        AiMarkdownService.Derived result = service.derive(markdown);

        assertTrue(result.getHtml().contains("<table>"));
        assertTrue(result.getHtml().contains("<del>旧</del>"));
        assertTrue(result.getHtml().contains("type=\"checkbox\""));
        assertTrue(result.getHtml().contains("href=\"https://example.org/auto\""));
        assertTrue(result.getHtml().contains("id=\"ai-heading-1\""));
        assertTrue(result.getToc().contains("功能清单"));
        assertTrue(result.getToc().contains("ai-heading-1"));

        ArticleReferenceReq doubleLink = result.getReferences().stream()
                .filter(reference -> ArticleReferenceEnum.INNER.getType().equals(reference.getType()))
                .findFirst()
                .orElseThrow(AssertionError::new);
        assertEquals(Long.valueOf(123L), doubleLink.getTargetId());
        assertEquals("关联笔记", doubleLink.getTargetName());
    }

    @Test
    void stripsDangerousLinkAndImageProtocolsFromHtmlAndReferences() {
        String markdown = "[脚本](javascript:alert(1))\n"
                + "![内联数据](data:text/html;base64,PHNjcmlwdD4=)\n"
                + "<img src=x onerror=alert(1)>";

        AiMarkdownService.Derived result = service.derive(markdown);

        assertFalse(result.getHtml().contains("href=\"javascript:"));
        assertFalse(result.getHtml().contains("src=\"data:"));
        assertFalse(result.getHtml().contains("<img src=x"));
        assertTrue(result.getHtml().contains("&lt;img"));
        assertTrue(result.getReferences().isEmpty());
    }
}
