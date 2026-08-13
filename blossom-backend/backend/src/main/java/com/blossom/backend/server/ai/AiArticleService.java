package com.blossom.backend.server.ai;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.blossom.backend.base.search.EnableIndex;
import com.blossom.backend.base.search.message.IndexMsgTypeEnum;
import com.blossom.backend.server.article.draft.ArticleService;
import com.blossom.backend.server.article.draft.pojo.ArticleEntity;
import com.blossom.backend.server.article.log.ArticleLogService;
import com.blossom.backend.server.article.reference.ArticleReferenceService;
import com.blossom.backend.server.folder.pojo.FolderEntity;
import com.blossom.backend.server.utils.ArticleUtil;
import com.blossom.backend.server.utils.DocUtil;
import com.blossom.common.base.exception.XzException;
import com.blossom.common.base.exception.XzException404;
import com.blossom.common.base.util.DateUtils;
import com.blossom.common.base.util.json.JsonUtil;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@AllArgsConstructor
public class AiArticleService {

    private static final int MAX_MARKDOWN_BYTES = 2 * 1024 * 1024;
    private static final Pattern CLIENT_REQUEST_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,80}$");

    private final AiMapper mapper;
    private final ArticleService articleService;
    private final ArticleReferenceService referenceService;
    private final ArticleLogService logService;
    private final AiMarkdownService markdownService;
    private final AiAuditService auditService;

    public List<AiModels.FolderRes> folders(Long userId) {
        List<AiModels.FolderRes> result = new ArrayList<>();
        AiModels.FolderRes root = new AiModels.FolderRes();
        root.setId(0L);
        root.setParentId(0L);
        root.setName("根目录");
        root.setPath("/");
        root.setSort(0);
        result.add(root);
        List<FolderEntity> folders = mapper.listFolders(userId);
        Map<Long, FolderEntity> byId = new HashMap<>();
        for (FolderEntity folder : folders) {
            byId.put(folder.getId(), folder);
        }
        Map<Long, String> paths = new HashMap<>();
        for (FolderEntity folder : folders) {
            AiModels.FolderRes item = new AiModels.FolderRes();
            item.setId(folder.getId());
            item.setParentId(folder.getPid());
            item.setName(folder.getName());
            item.setPath(resolveFolderPath(folder, byId, paths, new HashSet<>()));
            item.setSort(folder.getSort());
            result.add(item);
        }
        return result;
    }

    private String resolveFolderPath(FolderEntity folder, Map<Long, FolderEntity> byId,
                                     Map<Long, String> paths, Set<Long> visiting) {
        String cached = paths.get(folder.getId());
        if (cached != null) {
            return cached;
        }
        if (!visiting.add(folder.getId())) {
            throw new XzException("AI-FOLDER-CYCLE", "文件夹层级存在循环，无法生成路径");
        }
        String parentPath = "/";
        if (folder.getPid() != null && folder.getPid() > 0) {
            FolderEntity parent = byId.get(folder.getPid());
            if (parent != null) {
                parentPath = resolveFolderPath(parent, byId, paths, visiting);
            }
        }
        String safeName = folder.getName() == null ? String.valueOf(folder.getId()) : folder.getName().replace("/", "／");
        String path = parentPath + safeName + "/";
        paths.put(folder.getId(), path);
        visiting.remove(folder.getId());
        return path;
    }

    public AiModels.ArticleCursorRes list(Long userId, String cursor, Long folderId, Date updatedAfter, Integer requestedLimit) {
        int limit = normalizeLimit(requestedLimit);
        if (folderId != null) {
            validateFolder(folderId, userId);
        }
        List<ArticleEntity> rows = mapper.listArticles(userId, parseCursor(cursor), folderId, updatedAfter, limit + 1);
        boolean more = rows.size() > limit;
        if (more) {
            rows = new ArrayList<>(rows.subList(0, limit));
        }
        AiModels.ArticleCursorRes response = new AiModels.ArticleCursorRes();
        List<AiModels.ArticleSummaryRes> items = new ArrayList<>();
        for (ArticleEntity row : rows) {
            items.add(toSummary(row, null));
        }
        response.setItems(items);
        if (more && !rows.isEmpty()) {
            response.setNextCursor(String.valueOf(rows.get(rows.size() - 1).getId()));
        }
        return response;
    }

    public AiModels.ArticleCursorRes search(Long userId, String query, String cursor, Integer requestedLimit) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty() || q.length() > 500) {
            throw new XzException("AI-QUERY-INVALID", "q 必须为 1-500 个字符");
        }
        int limit = normalizeLimit(requestedLimit);
        List<ArticleEntity> rows = mapper.searchArticles(userId, q, parseCursor(cursor), limit + 1);
        boolean more = rows.size() > limit;
        if (more) {
            rows = new ArrayList<>(rows.subList(0, limit));
        }
        List<AiModels.ArticleSummaryRes> items = new ArrayList<>();
        for (ArticleEntity row : rows) {
            items.add(toSummary(row, snippet(row.getMarkdown(), q)));
        }
        AiModels.ArticleCursorRes result = new AiModels.ArticleCursorRes();
        result.setItems(items);
        if (more && !rows.isEmpty()) {
            result.setNextCursor(String.valueOf(rows.get(rows.size() - 1).getId()));
        }
        return result;
    }

    public AiModels.ArticleDetailRes get(Long userId, Long id) {
        ArticleEntity article = mapper.selectArticle(id, userId);
        XzException404.throwBy(article == null, "文章不存在或无权访问");
        return toDetail(article);
    }

    @Transactional(rollbackFor = Exception.class)
    public AiModels.ArticleDetailRes create(Long userId, Long tokenId, AiModels.ArticleCreateReq req) {
        validateClientRequestId(req.getClientRequestId());
        validateFolder(req.getFolderId(), userId);
        validateMarkdown(req.getMarkdown());
        String title = normalizeTitle(req.getTitle());
        String tags = normalizeTags(req.getTags());
        String hash = requestHash(req);

        Reservation reservation = reserve(userId, tokenId, "ARTICLE_CREATE", req.getClientRequestId(), hash, null);
        if (reservation.getReplayResourceId() != null) {
            AiModels.ArticleDetailRes replay = get(userId, reservation.getReplayResourceId());
            auditService.successInCurrentTransaction(userId, "ARTICLE_CREATE", replay.getId(),
                    null, replay.getRevision(), req.getClientRequestId());
            return replay;
        }

        AiMarkdownService.Derived derived = markdownService.derive(req.getMarkdown());
        ArticleEntity article = new ArticleEntity();
        article.setPid(req.getFolderId());
        article.setName(title);
        article.setTags(tags);
        article.setSort(mapper.selectMaxArticleSort(req.getFolderId(), userId) + 1);
        article.setMarkdown(req.getMarkdown());
        article.setHtml(derived.getHtml());
        article.setToc(derived.getToc());
        article.setWords(ArticleUtil.statWords(req.getMarkdown()));
        article.setVersion(1);
        article.setRevision(1L);
        article.setUpdMarkdownTime(DateUtils.date());
        article.setUserId(userId);
        article.setReferences(derived.getReferences());
        articleService.insertWithDerivedContent(article);
        // version=0 表示创建前的空正文；新建正文自身从 contentVersion=1 开始。
        logService.insertSync(article.getId(), 0, "");
        if (mapper.updateIdempotencyResource(reservation.getIdempotencyId(), tokenId, article.getId()) != 1) {
            throw new XzException("AI-IDEMPOTENCY-FAILED", "创建结果无法写入幂等记录");
        }
        AiModels.ArticleDetailRes result = get(userId, article.getId());
        auditService.successInCurrentTransaction(userId, "ARTICLE_CREATE", article.getId(),
                null, result.getRevision(), req.getClientRequestId());
        return result;
    }

    @EnableIndex(type = IndexMsgTypeEnum.ADD, id = "#articleId")
    @Transactional(rollbackFor = Exception.class)
    public AiModels.ArticleDetailRes update(Long userId, Long tokenId, Long articleId,
                                             AiModels.ArticleUpdateReq req) {
        validateClientRequestId(req.getClientRequestId());
        if (req.getFolderId() != null) {
            validateFolder(req.getFolderId(), userId);
        }
        if (req.getMarkdown() != null && CollUtil.isNotEmpty(req.getEdits())) {
            throw new XzException("AI-UPDATE-INVALID", "markdown 全文替换与 edits 精确编辑不能同时提供");
        }
        String operation = "ARTICLE_UPDATE_" + articleId;
        Reservation reservation = reserve(userId, tokenId, operation, req.getClientRequestId(), requestHash(req), articleId);
        if (reservation.getReplayResourceId() != null) {
            AiModels.ArticleDetailRes replay = get(userId, reservation.getReplayResourceId());
            auditService.successInCurrentTransaction(userId, "ARTICLE_UPDATE", articleId,
                    req.getExpectedRevision(), replay.getRevision(), req.getClientRequestId());
            return replay;
        }

        ArticleEntity before = mapper.selectArticle(articleId, userId);
        XzException404.throwBy(before == null, "文章不存在或无权修改");
        if (!req.getExpectedRevision().equals(before.getRevision())) {
            throw new XzException("ARTICLE-CONFLICT", "文章已被其他客户端修改，请重新读取后再保存");
        }

        AiEntities.ArticleMutation mutation = new AiEntities.ArticleMutation();
        mutation.setId(articleId);
        mutation.setUserId(userId);
        mutation.setExpectedRevision(req.getExpectedRevision());
        mutation.setFolderId(req.getFolderId());
        if (req.getFolderId() != null) {
            mutation.setSort(mapper.selectMaxArticleSort(req.getFolderId(), userId) + 1);
        }
        if (req.getTitle() != null) {
            mutation.setTitle(normalizeTitle(req.getTitle()));
        }
        if (req.getTags() != null) {
            mutation.setTags(normalizeTags(req.getTags()));
        }

        String markdown = req.getMarkdown();
        if (markdown == null && CollUtil.isNotEmpty(req.getEdits())) {
            markdown = applyExactEdits(before.getMarkdown(), req.getEdits());
        }
        AiMarkdownService.Derived derived = null;
        if (markdown != null) {
            validateMarkdown(markdown);
            derived = markdownService.derive(markdown);
            mutation.setMarkdown(markdown);
            mutation.setHtml(derived.getHtml());
            mutation.setToc(derived.getToc());
            mutation.setWords(ArticleUtil.statWords(markdown));
            mutation.setMarkdownUpdatedAt(DateUtils.date());
        }
        if (mutation.getFolderId() == null && mutation.getTitle() == null && mutation.getTags() == null
                && mutation.getMarkdown() == null) {
            throw new XzException("AI-UPDATE-EMPTY", "至少提供 title、tags、folderId、markdown 或 edits 之一");
        }

        int affected = mapper.updateArticle(mutation);
        if (affected != 1) {
            ArticleEntity current = mapper.selectArticle(articleId, userId);
            if (current == null) {
                throw new XzException("ARTICLE-NOT-FOUND", "文章不存在或无权修改");
            }
            throw new XzException("ARTICLE-CONFLICT", "文章已被其他客户端修改，请重新读取后再保存");
        }
        if (derived != null) {
            String sourceName = mutation.getTitle() == null ? before.getName() : mutation.getTitle();
            referenceService.bind(userId, articleId, sourceName, derived.getReferences());
            logService.insertSync(articleId, before.getVersion(), before.getMarkdown());
        }
        if (mutation.getTitle() != null) {
            referenceService.updateInnerName(userId, articleId, mutation.getTitle());
        }
        AiModels.ArticleDetailRes result = get(userId, articleId);
        auditService.successInCurrentTransaction(userId, "ARTICLE_UPDATE", articleId,
                req.getExpectedRevision(), result.getRevision(), req.getClientRequestId());
        return result;
    }

    private Reservation reserve(Long userId, Long tokenId, String operation, String key,
                                String hash, Long resourceId) {
        mapper.deleteExpiredIdempotency(tokenId, operation, key);
        AiEntities.Idempotency existing = mapper.selectIdempotency(tokenId, operation, key);
        if (existing != null) {
            validateReplay(existing, hash);
            if (existing.getResourceId() == null) {
                throw new XzException("AI-IDEMPOTENCY-IN-PROGRESS", "相同请求正在处理中，请稍后重试");
            }
            return new Reservation(existing.getId(), existing.getResourceId());
        }
        AiEntities.Idempotency record = new AiEntities.Idempotency();
        record.setUserId(userId);
        record.setTokenId(tokenId);
        record.setOperation(operation);
        record.setIdempotencyKey(key);
        record.setRequestHash(hash);
        record.setResourceId(resourceId);
        if (mapper.reserveIdempotency(record) == 1) {
            return new Reservation(record.getId(), null);
        }
        existing = mapper.selectIdempotency(tokenId, operation, key);
        if (existing == null) {
            throw new XzException("AI-IDEMPOTENCY-IN-PROGRESS", "相同请求正在处理中，请稍后重试");
        }
        validateReplay(existing, hash);
        if (existing.getResourceId() == null) {
            throw new XzException("AI-IDEMPOTENCY-IN-PROGRESS", "相同请求正在处理中，请稍后重试");
        }
        return new Reservation(existing.getId(), existing.getResourceId());
    }

    private void validateReplay(AiEntities.Idempotency existing, String hash) {
        if (!hash.equals(existing.getRequestHash())) {
            throw new XzException("AI-IDEMPOTENCY-KEY-REUSED", "clientRequestId 已用于不同请求载荷");
        }
    }

    String applyExactEdits(String original, List<AiModels.ExactEdit> edits) {
        String result = original == null ? "" : original;
        for (int i = 0; i < edits.size(); i++) {
            AiModels.ExactEdit edit = edits.get(i);
            String oldText = edit.getOldText();
            if (StrUtil.isEmpty(oldText) || edit.getNewText() == null) {
                throw new XzException("ARTICLE-EDIT-MISMATCH", "精确编辑的 oldText/newText 不合法");
            }
            int first = result.indexOf(oldText);
            if (first < 0 || result.indexOf(oldText, first + 1) >= 0) {
                throw new XzException("ARTICLE-EDIT-MISMATCH",
                        "第 " + (i + 1) + " 个 oldText 必须在当前正文中恰好出现一次");
            }
            result = result.substring(0, first) + edit.getNewText() + result.substring(first + oldText.length());
            validateMarkdown(result);
        }
        return result;
    }

    private void validateFolder(Long folderId, Long userId) {
        if (folderId == null || folderId < 0) {
            throw new XzException("AI-FOLDER-INVALID", "folderId 不能为空且不能小于 0");
        }
        if (folderId != 0L && mapper.countOwnedFolder(folderId, userId) != 1) {
            throw new XzException("AI-FOLDER-NOT-FOUND", "文件夹不存在或不属于当前用户");
        }
    }

    private void validateMarkdown(String markdown) {
        if (markdown == null || markdown.getBytes(StandardCharsets.UTF_8).length > MAX_MARKDOWN_BYTES) {
            throw new XzException("AI-MARKDOWN-TOO-LARGE", "markdown UTF-8 大小不能超过 2MiB");
        }
    }

    private void validateClientRequestId(String value) {
        if (value == null || !CLIENT_REQUEST_ID.matcher(value).matches()) {
            throw new XzException("AI-REQUEST-ID-INVALID", "clientRequestId 仅允许 1-80 位字母、数字、点、冒号、下划线或短横线");
        }
    }

    private String normalizeTitle(String value) {
        String title = value == null ? "" : value.trim();
        if (title.isEmpty() || title.length() > 255) {
            throw new XzException("AI-TITLE-INVALID", "标题必须为 1-255 个字符");
        }
        return title;
    }

    private String normalizeTags(List<String> values) {
        if (CollUtil.isEmpty(values)) {
            return "";
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            String tag = value == null ? "" : value.trim();
            if (tag.isEmpty() || tag.length() > 40 || tag.contains(",")) {
                throw new XzException("AI-TAG-INVALID", "标签不能为空、最长 40 字符且不能包含逗号");
            }
            unique.add(tag);
        }
        String tags = String.join(",", unique);
        if (tags.length() > 255) {
            throw new XzException("AI-TAG-INVALID", "标签总长度不能超过 255 个字符");
        }
        return tags;
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return 50;
        }
        if (limit < 1 || limit > 100) {
            throw new XzException("AI-LIMIT-INVALID", "limit 必须在 1-100 之间");
        }
        return limit;
    }

    private Long parseCursor(String cursor) {
        if (StrUtil.isBlank(cursor)) {
            return null;
        }
        if (cursor.length() > 30 || !cursor.matches("[0-9]+")) {
            throw new XzException("AI-CURSOR-INVALID", "cursor 无效");
        }
        try {
            long value = Long.parseLong(cursor);
            if (value < 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            throw new XzException("AI-CURSOR-INVALID", "cursor 无效");
        }
    }

    private AiModels.ArticleSummaryRes toSummary(ArticleEntity article, String snippet) {
        AiModels.ArticleSummaryRes result = new AiModels.ArticleSummaryRes();
        result.setId(article.getId());
        result.setFolderId(article.getPid());
        result.setTitle(article.getName());
        result.setTags(DocUtil.toTagList(article.getTags()));
        result.setWords(article.getWords());
        result.setContentVersion(article.getVersion());
        result.setRevision(article.getRevision());
        result.setCreatedAt(article.getCreTime());
        result.setUpdatedAt(article.getUpdTime());
        result.setSnippet(snippet);
        return result;
    }

    private AiModels.ArticleDetailRes toDetail(ArticleEntity article) {
        AiModels.ArticleDetailRes result = new AiModels.ArticleDetailRes();
        AiModels.ArticleSummaryRes summary = toSummary(article, null);
        result.setId(summary.getId());
        result.setFolderId(summary.getFolderId());
        result.setTitle(summary.getTitle());
        result.setTags(summary.getTags());
        result.setWords(summary.getWords());
        result.setContentVersion(summary.getContentVersion());
        result.setRevision(summary.getRevision());
        result.setCreatedAt(summary.getCreatedAt());
        result.setUpdatedAt(summary.getUpdatedAt());
        result.setMarkdown(article.getMarkdown() == null ? "" : article.getMarkdown());
        return result;
    }

    private String snippet(String markdown, String query) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        String lower = markdown.toLowerCase(Locale.ROOT);
        int at = lower.indexOf(query.toLowerCase(Locale.ROOT));
        if (at < 0) {
            at = 0;
        }
        int begin = Math.max(0, at - 80);
        int end = Math.min(markdown.length(), at + query.length() + 120);
        return (begin > 0 ? "…" : "") + markdown.substring(begin, end).replaceAll("\\s+", " ")
                + (end < markdown.length() ? "…" : "");
    }

    private String requestHash(Object request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(JsonUtil.toJson(request).getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(64);
            for (byte b : digest) {
                value.append(String.format("%02x", b & 0xff));
            }
            return value.toString();
        } catch (Exception e) {
            throw new XzException("AI-REQUEST-HASH-FAILED", "请求摘要计算失败");
        }
    }

    @lombok.Value
    private static class Reservation {
        Long idempotencyId;
        Long replayResourceId;
    }
}
