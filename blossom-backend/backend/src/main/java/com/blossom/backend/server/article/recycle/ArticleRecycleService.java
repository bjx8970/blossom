package com.blossom.backend.server.article.recycle;

import cn.hutool.core.util.ObjUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.blossom.backend.base.param.ParamEnum;
import com.blossom.backend.base.param.ParamService;
import com.blossom.backend.base.param.pojo.ParamEntity;
import com.blossom.backend.base.search.EnableIndex;
import com.blossom.backend.base.search.message.IndexMsgTypeEnum;
import com.blossom.backend.server.article.recycle.pojo.ArticleRecycleEntity;
import com.blossom.backend.server.article.reference.ArticleReferenceService;
import com.blossom.backend.server.folder.FolderService;
import com.blossom.backend.server.folder.pojo.FolderEntity;
import com.blossom.common.base.util.DateUtils;
import com.blossom.common.base.exception.XzException404;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

/**
 * 文章回收站
 *
 * @author xzzz
 * @since 1.10.0
 */
@Slf4j
@Service
@AllArgsConstructor
public class ArticleRecycleService extends ServiceImpl<ArticleRecycleMapper, ArticleRecycleEntity> {

    private final FolderService folderService;
    private final ParamService paramService;
    private final ArticleReferenceService referenceService;


    /**
     * 查询回收站所有内容
     *
     * @param userId 用户ID
     */
    public List<ArticleRecycleEntity> listAll(Long userId) {
        return baseMapper.listAll(userId);
    }

    /**
     * 根据ID查询
     *
     * @param id 文章ID
     */
    public ArticleRecycleEntity selectById(Long id, Long userId) {
        return baseMapper.selectOne(new LambdaQueryWrapper<ArticleRecycleEntity>()
                .eq(ArticleRecycleEntity::getId, id)
                .eq(ArticleRecycleEntity::getUserId, userId));
    }

    /**
     * 还原数据
     *
     * @param id 文章ID
     */
    @EnableIndex(type = IndexMsgTypeEnum.ADD, id = "#id")
    @Transactional(rollbackFor = Exception.class)
    public void restore(Long userId, Long id) {
        ArticleRecycleEntity article = selectById(id, userId);
        XzException404.throwBy(article == null, "回收站文章不存在或无权操作");
        FolderEntity folder = folderService.selectById(article.getPid(), userId);
        if (ObjUtil.isNull(folder)) {
            XzException404.throwBy(baseMapper.restore(id, 0L, userId) != 1, "回收站文章还原失败");
        } else {
            XzException404.throwBy(baseMapper.restore(id, folder.getId(), userId) != 1, "回收站文章还原失败");
        }
        int affected = baseMapper.delete(new LambdaQueryWrapper<ArticleRecycleEntity>()
                .eq(ArticleRecycleEntity::getId, id)
                .eq(ArticleRecycleEntity::getUserId, userId));
        XzException404.throwBy(affected != 1, "回收站文章不存在或无权操作");
        // 主动引用在删除文章时一并删除，恢复时无法从旧回收数据重建；保留历史行为，
        // 至少把其他文章指向本文的未知双链恢复为正常名称。
        referenceService.updateToKnown(userId, id, article.getName());
    }

    /**
     * 每天凌晨4点执行
     *
     * @Scheduled(cron = "0 0/1 * * * ?")
     */
    @Scheduled(cron = "0 0 04 * * ?")
    @Transactional(rollbackFor = Exception.class)
    public void delExpireRecycle() {
        ParamEntity param = paramService.getValue(ParamEnum.ARTICLE_RECYCLE_EXP_DAYS);
        int expireDay = -60;
        if (param != null) {
            expireDay = Integer.parseInt(param.getParamValue());
        }
        if (expireDay > 0) {
            expireDay = expireDay * -1;
        }

        log.info("[BLOSSOM] 删除{}日前的文章回收站记录", Math.abs(expireDay));
        Date expireDate = DateUtils.offsetDay(DateUtils.date(), expireDay);
        LambdaQueryWrapper<ArticleRecycleEntity> where = new LambdaQueryWrapper<>();
        where.lt(ArticleRecycleEntity::getDelTime, DateUtils.toYMD(expireDate));
        baseMapper.delete(where);
    }
}
