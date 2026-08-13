package com.blossom.backend.server.ai;

import com.blossom.backend.server.article.reference.ArticleReferenceEnum;
import com.blossom.backend.server.article.reference.pojo.ArticleReferenceReq;
import com.blossom.common.base.exception.XzException;
import com.blossom.common.base.util.json.JsonUtil;
import lombok.Data;
import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.AttributeProvider;
import org.commonmark.renderer.html.AttributeProviderContext;
import org.commonmark.renderer.html.AttributeProviderFactory;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 服务端 Markdown 权威派生器。
 *
 * <p>使用 commonmark-java 解析 AST，统一为桌面端和 AI 写入派生 HTML、TOC 与引用。
 * 原始 HTML 永远转义，URL 同时经过协议白名单和渲染器清洗。启用的扩展与桌面端常用
 * GFM 语法一致：表格、删除线、任务列表和自动链接。</p>
 */
@Service
public class AiMarkdownService {

    private static final Pattern INNER_ARTICLE_TITLE = Pattern.compile("##([0-9]{1,19})##");
    private static final int MAX_REFERENCE_NAME_CHARS = 500;
    private static final int MAX_REFERENCE_URL_CHARS = 2000;
    private static final int MAX_MARKDOWN_BYTES = 2 * 1024 * 1024;
    private static final List<Extension> EXTENSIONS = Collections.unmodifiableList(Arrays.asList(
            TablesExtension.create(),
            StrikethroughExtension.create(),
            TaskListItemsExtension.create(),
            AutolinkExtension.create()
    ));

    private final Parser parser = Parser.builder().extensions(EXTENSIONS).build();

    public Derived derive(String markdown) {
        if (markdown != null && markdown.getBytes(StandardCharsets.UTF_8).length > MAX_MARKDOWN_BYTES) {
            throw new XzException("ARTICLE-MARKDOWN-TOO-LARGE", "markdown UTF-8 大小不能超过 2MiB");
        }
        String source = markdown == null ? "" : markdown.replace("\r\n", "\n").replace('\r', '\n');
        Node document = parser.parse(source);
        RenderState state = inspect(document);
        HtmlRenderer renderer = HtmlRenderer.builder()
                .extensions(EXTENSIONS)
                .escapeHtml(true)
                .sanitizeUrls(true)
                .attributeProviderFactory(new DerivedAttributeProviderFactory(state))
                .build();
        String html = renderer.render(document);

        Derived result = new Derived();
        result.setHtml(html.isEmpty() ? "<p></p>" : html);
        result.setToc(JsonUtil.toJson(state.toc));
        result.setReferences(state.references);
        return result;
    }

    private RenderState inspect(Node document) {
        final RenderState state = new RenderState();
        document.accept(new AbstractVisitor() {
            @Override
            public void visit(Heading heading) {
                String id = "ai-heading-" + (++state.headingIndex);
                state.headingIds.put(heading, id);
                TocItem item = new TocItem();
                item.setContent(safeTocText(plainText(heading)));
                item.setClazz("toc-" + heading.getLevel());
                item.setId(id);
                state.toc.add(item);
                visitChildren(heading);
            }

            @Override
            public void visit(Link link) {
                collectReference(state, link, false, link.getDestination(), link.getTitle());
                visitChildren(link);
            }

            @Override
            public void visit(Image image) {
                collectReference(state, image, true, image.getDestination(), image.getTitle());
                visitChildren(image);
            }
        });
        return state;
    }

    private void collectReference(RenderState state, Node node, boolean image,
                                  String destination, String title) {
        if (!isSafeUrl(destination, image)) {
            state.unsafeUrls.add(node);
            return;
        }
        String name = plainText(node);
        if (image) {
            addReference(state, ArticleReferenceEnum.FILE.getType(), 0L, name, destination);
            return;
        }

        Long targetId = parseInnerArticleId(title);
        if (targetId != null) {
            addReference(state, ArticleReferenceEnum.INNER.getType(), targetId, name, destination);
        } else {
            addReference(state, ArticleReferenceEnum.OUTSIDE.getType(), 0L, name, destination);
        }
    }

    private void addReference(RenderState state, Integer type, Long targetId,
                              String name, String destination) {
        String key = type + "\n" + targetId + "\n" + destination;
        if (!state.referenceKeys.add(key)) {
            return;
        }
        ArticleReferenceReq reference = new ArticleReferenceReq();
        reference.setTargetId(targetId);
        reference.setTargetName(truncate(name == null ? "" : name, MAX_REFERENCE_NAME_CHARS));
        reference.setTargetUrl(destination);
        reference.setType(type);
        state.references.add(reference);
    }

    private Long parseInnerArticleId(String title) {
        if (title == null) {
            return null;
        }
        Matcher matcher = INNER_ARTICLE_TITLE.matcher(title);
        if (!matcher.find()) {
            return null;
        }
        try {
            long value = Long.parseLong(matcher.group(1));
            return value > 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String plainText(Node node) {
        StringBuilder text = new StringBuilder();
        appendPlainText(node, text);
        return text.toString().replaceAll("\\s+", " ").trim();
    }

    private void appendPlainText(Node node, StringBuilder text) {
        if (node instanceof Text) {
            text.append(((Text) node).getLiteral());
            return;
        }
        if (node instanceof Code) {
            text.append(((Code) node).getLiteral());
            return;
        }
        if (node instanceof SoftLineBreak || node instanceof HardLineBreak) {
            text.append(' ');
            return;
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            appendPlainText(child, text);
        }
    }

    private boolean isSafeUrl(String value, boolean image) {
        if (value == null || value.isEmpty() || value.length() > MAX_REFERENCE_URL_CHARS || !value.equals(value.trim())
                || value.startsWith("//")) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current <= 0x1f || current == 0x7f) {
                return false;
            }
        }
        if (value.startsWith("/") || value.startsWith("./") || value.startsWith("../")) {
            return true;
        }
        if (value.startsWith("#")) {
            return !image;
        }
        try {
            String scheme = URI.create(value).getScheme();
            if (scheme == null) {
                return true;
            }
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                return true;
            }
            return !image && "mailto".equalsIgnoreCase(scheme);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private String safeTocText(String text) {
        return text.replace('<', '＜').replace('>', '＞').replace('&', '＆');
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static class DerivedAttributeProviderFactory implements AttributeProviderFactory {
        private final RenderState state;

        private DerivedAttributeProviderFactory(RenderState state) {
            this.state = state;
        }

        @Override
        public AttributeProvider create(AttributeProviderContext context) {
            return new AttributeProvider() {
                @Override
                public void setAttributes(Node node, String tagName, Map<String, String> attributes) {
                    String headingId = state.headingIds.get(node);
                    if (headingId != null) {
                        attributes.put("id", headingId);
                    }
                    if (state.unsafeUrls.contains(node)) {
                        attributes.remove(node instanceof Image ? "src" : "href");
                        return;
                    }
                    if (node instanceof Link) {
                        attributes.put("target", "_blank");
                        attributes.put("rel", "noopener noreferrer");
                    }
                }
            };
        }
    }

    private static class RenderState {
        private int headingIndex;
        private final Map<Node, String> headingIds = new IdentityHashMap<>();
        private final Set<Node> unsafeUrls = Collections.newSetFromMap(new IdentityHashMap<Node, Boolean>());
        private final Set<String> referenceKeys = new HashSet<>();
        private final List<TocItem> toc = new ArrayList<>();
        private final List<ArticleReferenceReq> references = new ArrayList<>();
    }

    @Data
    public static class Derived {
        private String html;
        private String toc;
        private List<ArticleReferenceReq> references;
    }

    @Data
    private static class TocItem {
        private String content;
        private String clazz;
        private String id;
    }
}
