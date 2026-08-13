package com.blossom.backend.server.ai;

import com.blossom.backend.base.auth.AuthContext;
import com.blossom.common.base.pojo.R;
import lombok.AllArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Date;

@RestController
@AllArgsConstructor
@RequestMapping("/api/ai/v1")
public class AiArticleController {

    private final AiArticleService service;
    private final AiAuditService auditService;

    @GetMapping("/folders")
    public R<List<AiModels.FolderRes>> folders(HttpServletRequest request) {
        Long userId = AuthContext.getUserId();
        try {
            List<AiModels.FolderRes> data = service.folders(userId);
            auditService.success(userId, "FOLDERS_LIST", null, null, null, request);
            return R.ok(data);
        } catch (RuntimeException e) {
            auditService.failure(userId, "FOLDERS_LIST", null, null, e, request);
            throw e;
        }
    }

    @GetMapping({"/notes", "/articles"})
    public R<AiModels.ArticleCursorRes> list(@RequestParam(value = "cursor", required = false) String cursor,
                                             @RequestParam(value = "folderId", required = false) Long folderId,
                                             @RequestParam(value = "updatedAfter", required = false)
                                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Date updatedAfter,
                                             @RequestParam(value = "limit", required = false) Integer limit,
                                             HttpServletRequest request) {
        Long userId = AuthContext.getUserId();
        try {
            AiModels.ArticleCursorRes data = service.list(userId, cursor, folderId, updatedAfter, limit);
            auditService.success(userId, "ARTICLES_LIST", null, null, null, request);
            return R.ok(data);
        } catch (RuntimeException e) {
            auditService.failure(userId, "ARTICLES_LIST", null, null, e, request);
            throw e;
        }
    }

    @GetMapping({"/notes/search", "/articles/search"})
    public R<AiModels.ArticleCursorRes> search(@RequestParam("q") String query,
                                                       @RequestParam(value = "cursor", required = false) String cursor,
                                                       @RequestParam(value = "limit", required = false) Integer limit,
                                                       HttpServletRequest request) {
        Long userId = AuthContext.getUserId();
        try {
            AiModels.ArticleCursorRes data = service.search(userId, query, cursor, limit);
            auditService.success(userId, "ARTICLES_SEARCH", null, null, null, request);
            return R.ok(data);
        } catch (RuntimeException e) {
            auditService.failure(userId, "ARTICLES_SEARCH", null, null, e, request);
            throw e;
        }
    }

    @GetMapping({"/notes/{id}", "/articles/{id}"})
    public R<AiModels.ArticleDetailRes> get(@PathVariable("id") Long id, HttpServletRequest request) {
        Long userId = AuthContext.getUserId();
        try {
            AiModels.ArticleDetailRes data = service.get(userId, id);
            auditService.success(userId, "ARTICLE_GET", id, null, data.getRevision(), request);
            return R.ok(data);
        } catch (RuntimeException e) {
            auditService.failure(userId, "ARTICLE_GET", id, null, e, request);
            throw e;
        }
    }

    @PostMapping({"/notes", "/articles"})
    public R<AiModels.ArticleDetailRes> create(@Validated @RequestBody AiModels.ArticleCreateReq req,
                                               HttpServletRequest request) {
        Long userId = AuthContext.getUserId();
        try {
            AiModels.ArticleDetailRes data = service.create(userId, AiAuthContext.tokenId(), req);
            return R.ok(data);
        } catch (RuntimeException e) {
            auditService.failure(userId, "ARTICLE_CREATE", null, null,
                    req.getClientRequestId(), e, request);
            throw e;
        }
    }

    @PatchMapping({"/notes/{id}", "/articles/{id}"})
    public R<AiModels.ArticleDetailRes> update(@PathVariable("id") Long id,
                                               @Validated @RequestBody AiModels.ArticleUpdateReq req,
                                               HttpServletRequest request) {
        Long userId = AuthContext.getUserId();
        try {
            AiModels.ArticleDetailRes data = service.update(userId, AiAuthContext.tokenId(), id, req);
            return R.ok(data);
        } catch (RuntimeException e) {
            auditService.failure(userId, "ARTICLE_UPDATE", id, req.getExpectedRevision(),
                    req.getClientRequestId(), e, request);
            throw e;
        }
    }
}
