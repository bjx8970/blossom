package com.blossom.backend.server.article.draft;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.blossom.backend.base.search.EnableIndex;
import com.blossom.backend.base.search.message.IndexMsgTypeEnum;
import com.blossom.backend.server.article.TagEnum;
import com.blossom.backend.server.article.draft.pojo.ArticleEntity;
import com.blossom.backend.server.article.draft.pojo.ArticleQueryReq;
import com.blossom.backend.server.article.log.ArticleLogService;
import com.blossom.backend.server.article.open.ArticleOpenMapper;
import com.blossom.backend.server.article.recycle.ArticleRecycleMapper;
import com.blossom.backend.server.article.reference.ArticleReferenceService;
import com.blossom.backend.server.article.view.ArticleViewService;
import com.blossom.backend.server.doc.pojo.DocTreeRes;
import com.blossom.backend.server.folder.FolderService;
import com.blossom.backend.server.folder.FolderTypeEnum;
import com.blossom.backend.server.folder.pojo.FolderEntity;
import com.blossom.backend.server.utils.ArticleUtil;
import com.blossom.backend.server.utils.DocUtil;
import com.blossom.common.base.exception.XzException404;
import com.blossom.common.base.exception.XzException;
import com.blossom.common.base.util.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 文章
 *
 * @author xzzz
 */
@Slf4j
@Service
public class ArticleService extends ServiceImpl<ArticleMapper, ArticleEntity> {
    private ArticleReferenceService referenceService;
    private ArticleViewService viewService;
    private ArticleLogService logService;
    private ArticleOpenMapper openMapper;
    private ArticleRecycleMapper recycleMapper;
    private FolderService folderService;

    @Autowired
    public void setReferenceService(ArticleReferenceService referenceService) {
        this.referenceService = referenceService;
    }

    @Autowired
    public void setRecycleMapper(ArticleRecycleMapper recycleMapper) {
        this.recycleMapper = recycleMapper;
    }

    @Autowired
    public void setViewService(ArticleViewService viewService) {
        this.viewService = viewService;
    }

    @Autowired
    public void setLogService(ArticleLogService logService) {
        this.logService = logService;
    }

    @Autowired
    public void setOpenMapper(ArticleOpenMapper openMapper) {
        this.openMapper = openMapper;
    }

    @Autowired
    public void setFolderService(FolderService folderService) {
        this.folderService = folderService;
    }

    /**
     * 获取指定ID的正文内容
     *
     * @param ids         ID集合
     * @param contentType 正文类型 MARKDOWN/HTML
     * @return 内容
     */
    public List<ArticleEntity> listAllContent(List<Long> ids, String contentType) {
        List<ArticleEntity> articles = baseMapper.listAllContent(ids, contentType.toUpperCase());
        if (CollUtil.isEmpty(articles)) {
            return new ArrayList<>();
        }
        return articles;
    }

    /**
     * 获取所有文章，包含markdown字段，用于索引的批量维护
     *
     * @return
     */
    public List<ArticleEntity> listAllIndexField() {
        List<ArticleEntity> articles = baseMapper.listAllIndexField();
        if (CollUtil.isEmpty(articles)) {
            return new ArrayList<>();
        }
        return articles;
    }

    /**
     * 查询列表
     * <p>避免在查询主要信息时返回正文信息造成的性能影响, 该接口不返回文章正文 toc/markdown/html</p>
     * <p>如需查询正文, 请使用{@link ArticleService#selectById} 或 {@link ArticleService#listAllContent}</p>
     */
    public List<ArticleEntity> listAll(ArticleQueryReq req) {
        List<ArticleEntity> articles = baseMapper.listAll(req.to(ArticleEntity.class));
        if (CollUtil.isEmpty(articles)) {
            return new ArrayList<>();
        }
        return articles;
    }

    /**
     * 树状列表
     *
     * @return DocTreeRes
     */
    public List<DocTreeRes> listTree(ArticleQueryReq req) {
        List<ArticleEntity> articles = baseMapper.listAll(req.to(ArticleEntity.class));
        List<DocTreeRes> articleTrees = new ArrayList<>(articles.size());
        for (ArticleEntity article : articles) {
            articleTrees.add(DocUtil.toDocTree(article));
        }
        return articleTrees;
    }

    /**
     * 根据ID查询
     *
     * @param id           文章ID
     * @param showToc      是否返回目录 json 内容
     * @param showMarkdown 是否返回 markdown 正文
     * @param showHtml     是否返回 html 正文
     */
    public ArticleEntity selectById(Long id, boolean showToc, boolean showMarkdown, boolean showHtml, Long userId) {
        QueryWrapper<ArticleEntity> where = new QueryWrapper<>();
        List<String> column = CollUtil.newArrayList("id", "pid", "name", "icon", "tags", "sort", "cover", "describes", "star_status",
                "open_status", "pv", "uv", "likes", "words", "version", "revision", "cre_time", "upd_time");
        if (showToc) {
            column.add(TagEnum.toc.name());
        }
        if (showMarkdown) {
            column.add("markdown");
        }
        if (showHtml) {
            column.add("html");
        }
        where.select(column);
        where.eq("id", id).eq("user_id", userId).last("limit 1");
        return baseMapper.selectOne(where);
    }

    /**
     * 新增
     */
    @EnableIndex(type = IndexMsgTypeEnum.ADD, id = "#req.id")
    @Transactional(rollbackFor = Exception.class)
    public ArticleEntity insert(ArticleEntity req) {
        validateArticleFolder(req.getPid(), req.getUserId());
        int affected = baseMapper.insert(req);
        if (affected != 1) {
            throw new XzException("ARTICLE-INSERT-FAILED", "文章创建失败");
        }
        return req;
    }

    /**
     * AI/import 等受信任服务端命令可显式提交已经派生好的正文快照。
     * 普通桌面创建仍只创建元数据，随后由桌面扩展渲染器调用正文保存接口。
     */
    @EnableIndex(type = IndexMsgTypeEnum.ADD, id = "#req.id")
    @Transactional(rollbackFor = Exception.class)
    public ArticleEntity insertWithDerivedContent(ArticleEntity req) {
        ArticleEntity inserted = insert(req);
        if (req.getMarkdown() != null) {
            referenceService.bind(req.getUserId(), req.getId(), req.getName(), req.getReferences());
        }
        return inserted;
    }

    /**
     * 修改
     * <p>该接口只能修改文章的基本信息, 正文及版本修改请使用 {@link ArticleService#updateContentById(ArticleEntity)}
     */
    @EnableIndex(type = IndexMsgTypeEnum.ADD, id = "#req.id")
    @Transactional(rollbackFor = Exception.class)
    public Long update(ArticleEntity req) {
        XzException404.throwBy(req.getId() == null, "ID不得为空");
        if (req.getPid() != null) {
            validateArticleFolder(req.getPid(), req.getUserId());
        }
        if (Boolean.TRUE.equals(req.getIncrementRevision()) && req.getExpectedRevision() == null) {
            throw new XzException("CLIENT_UPGRADE_REQUIRED", "当前客户端未提供 expectedRevision，请升级后重试");
        }
        int affected = baseMapper.updById(req);
        if (affected == 0 && req.getExpectedRevision() != null) {
            throw new XzException("ARTICLE-CONFLICT", "文章已被其他客户端修改，请重新读取后再保存");
        }
        XzException404.throwBy(affected == 0, "文章不存在或无权修改");
        if(StrUtil.isNotBlank(req.getName())) {
            referenceService.updateInnerName(req.getUserId(), req.getId(), req.getName());
        }
        return req.getId();
    }

    private void validateArticleFolder(Long folderId, Long userId) {
        XzException404.throwBy(folderId == null || folderId < 0 || userId == null,
                "文章目录不存在或无权访问");
        if (folderId == 0L) {
            return;
        }
        FolderEntity folder = folderService.selectById(folderId, userId);
        XzException404.throwBy(folder == null || !FolderTypeEnum.ARTICLE.getType().equals(folder.getType()),
                "文章目录不存在或无权访问");
    }

    /**
     * 修改文章正文内容, 并更新字数字数
     *
     * @return 返回文章字数
     */
    @EnableIndex(type = IndexMsgTypeEnum.ADD, id = "#req.id")
    @Transactional(rollbackFor = Exception.class)
    public Integer updateContentById(ArticleEntity req) {
        XzException404.throwBy(req.getId() == null, "ID不得为空");
        if (req.getExpectedRevision() == null) {
            throw new XzException("CLIENT_UPGRADE_REQUIRED", "当前客户端未提供 expectedRevision，请升级后重试");
        }
        ArticleEntity before = selectById(req.getId(), false, true, false, req.getUserId());
        XzException404.throwBy(before == null, "文章不存在或无权修改");
        if (!req.getExpectedRevision().equals(before.getRevision())) {
            throw new XzException("ARTICLE-CONFLICT", "文章已被其他客户端修改，请重新读取后再保存");
        }
        if (req.getMarkdown() == null) {
            throw new XzException("ARTICLE-MARKDOWN-REQUIRED", "markdown 正文不能为空");
        }
        // 普通桌面端使用 Blossom 自有 marked 扩展渲染 KaTeX、Mermaid、Markmap、
        // Bilibili、双链和图片样式。这里保存客户端已经净化的派生结果，不能再用
        // AI 的安全 CommonMark 基线覆盖，否则公开文章会丢失现有渲染能力。
        if (req.getHtml() == null) {
            throw new XzException("ARTICLE-HTML-REQUIRED", "桌面端保存必须提交已净化的 html");
        }
        if (req.getToc() == null) {
            throw new XzException("ARTICLE-TOC-REQUIRED", "桌面端保存必须提交 toc");
        }
        req.setWords(ArticleUtil.statWords(req.getMarkdown()));
        req.setUpdMarkdownTime(DateUtils.date());
        int affected = baseMapper.updContentById(req);
        if (affected == 0 && req.getExpectedRevision() != null) {
            throw new XzException("ARTICLE-CONFLICT", "文章已被其他客户端修改，请重新读取后再保存");
        }
        XzException404.throwBy(affected == 0, "文章不存在或无权修改");
        referenceService.bind(req.getUserId(), req.getId(), before.getName(), req.getReferences());
        logService.insertSync(req.getId(), before.getVersion(), before.getMarkdown());
        return req.getWords();
    }

    /**
     * 删除文章
     * <p>1. 删除文章</p>
     * <p>2. 删除公开文章</p>
     *
     * @param id 文章ID
     * @since 1.10.0 删除时不校验文章内容, 被删除的文章会进入回收站, 可在回收站中查询
     */
    @EnableIndex(type = IndexMsgTypeEnum.DELETE, id = "#id")
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Long userId) {
        ArticleEntity article = selectById(id, false, true, true, userId);
        XzException404.throwBy(ObjUtil.isNull(article), "文章不存在");
        /*
        @since 1.10.0
        XzException400.throwBy(StrUtil.isNotBlank(article.getMarkdown()), "文章内容不为空, 请清空内容后再删除.");
         */
        if (recycleMapper.save(article.getId(), userId) != 1) {
            throw new XzException("ARTICLE-RECYCLE-SAVE-FAILED", "文章无法写入回收站");
        }
        // 删除文章
        int affected = baseMapper.delete(new LambdaQueryWrapper<ArticleEntity>()
                .eq(ArticleEntity::getId, id)
                .eq(ArticleEntity::getUserId, userId));
        XzException404.throwBy(affected != 1, "文章不存在或无权删除");
        // 删除公开文章
        openMapper.delById(id);
        // 删除主动引用
        referenceService.delete(id, userId);
        // 将被动引用中的名称修改为未知
        referenceService.updateToUnknown(userId, id);
        // 删除访问记录
        viewService.delete(id);
    }

    /**
     * 同步版本号
     * <p>将文章的 version 同步到 openVersion, 只有 open_status 为 1 才会修改成功
     */
    public void sync(Long id) {
        baseMapper.sync(id);
    }

    /**
     * 递增 UV 和 PV 数据
     * <p>PV 接口每调用一次都会递增, UV数据每个IP每天只会递增一次
     *
     * @param ip        ip
     * @param userAgent userAgent
     * @param id        文章ID
     */
    @Async
    @Transactional(rollbackFor = Exception.class)
    public void uvAndPv(String ip, String userAgent, Long id) {
        int uv = 0;
        if (viewService.uv(ip, userAgent, id)) {
            uv = 1;
        }
        baseMapper.uvAndPv(id, 1, uv);
    }


}
