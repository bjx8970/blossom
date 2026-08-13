package com.blossom.backend.server.article.draft;

import com.blossom.backend.server.article.draft.pojo.ArticleEntity;
import com.blossom.backend.server.article.log.ArticleLogService;
import com.blossom.backend.server.article.reference.ArticleReferenceService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class ArticleServiceRenderingCompatibilityTest {

    @Test
    void desktopSavePreservesSanitizedBlossomExtensionHtmlAndToc() {
        ArticleMapper mapper = Mockito.mock(ArticleMapper.class);
        ArticleReferenceService references = Mockito.mock(ArticleReferenceService.class);
        ArticleLogService logs = Mockito.mock(ArticleLogService.class);
        ArticleService service = new ArticleService();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "referenceService", references);
        ReflectionTestUtils.setField(service, "logService", logs);

        ArticleEntity before = new ArticleEntity();
        before.setId(7L);
        before.setName("扩展文章");
        before.setMarkdown("旧正文");
        before.setVersion(3);
        before.setRevision(9L);
        when(mapper.selectOne(any())).thenReturn(before);
        when(mapper.updContentById(any())).thenReturn(1);

        String extensionHtml = "<div class=\"mermaid\" data-processed=\"true\"><svg></svg></div>"
                + "<span class=\"katex\">x</span><div class=\"markmap\"></div>"
                + "<iframe src=\"https://player.bilibili.com/player.html?bvid=BV1\"></iframe>"
                + "<a data-bl-event=\"showArticleReferenceView\" data-bl-data=\"12\">双链</a>";
        String toc = "[{\"content\":\"标题\",\"clazz\":\"toc-1\",\"id\":\"heading-1\"}]";
        ArticleEntity update = new ArticleEntity();
        update.setId(7L);
        update.setUserId(1L);
        update.setExpectedRevision(9L);
        update.setMarkdown("```mermaid\ngraph TD; A-->B\n```");
        update.setHtml(extensionHtml);
        update.setToc(toc);
        update.setReferences(Collections.emptyList());

        service.updateContentById(update);

        assertEquals(extensionHtml, update.getHtml());
        assertEquals(toc, update.getToc());
    }
}
