package com.blossom.backend.server.article.log;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.blossom.backend.base.param.ParamEnum;
import com.blossom.backend.base.param.ParamService;
import com.blossom.backend.base.param.pojo.ParamEntity;
import com.blossom.backend.server.article.log.pojo.ArticleLogEntity;
import com.blossom.common.base.util.DateUtils;
import com.blossom.common.base.exception.XzException404;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

/**
 * 文章记录
 */
@Slf4j
@Service
@AllArgsConstructor
public class ArticleLogService extends ServiceImpl<ArticleLogMapper, ArticleLogEntity> {

    private final ParamService paramService;

    public List<ArticleLogEntity> listAll(Long articleId, Long userId) {
        return baseMapper.listAll(articleId, userId);
    }

    public String content(Long id, Long userId) {
        String content = baseMapper.selectContent(id, userId);
        XzException404.throwBy(content == null, "文章历史不存在或无权访问");
        return content;
    }

    /**
     * 新增记录
     */
    @Async
    @Transactional(rollbackFor = Exception.class)
    public void insert(Long articleId, Integer version, String markdown) {
        insertSync(articleId, version, markdown);
    }

    /**
     * 在调用方事务内保存快照。AI/CAS 写入用它确保历史与正文原子提交。
     */
    public void insertSync(Long articleId, Integer version, String markdown) {
        ArticleLogEntity log = new ArticleLogEntity();
        log.setArticleId(articleId);
        log.setVersion(version);
        log.setMarkdown(markdown == null ? "" : markdown);
        log.setCreTime(DateUtils.date());
        baseMapper.insert(log);
    }

    /**
     * 每天凌晨5点执行
     */
    @Scheduled(cron = "0 0 05 * * ?")
    @Transactional(rollbackFor = Exception.class)
    public void delExpireLog() {
        ParamEntity param = paramService.getValue(ParamEnum.ARTICLE_LOG_EXP_DAYS);
        int expireDay = -60;
        if (param != null) {
            expireDay = Integer.parseInt(param.getParamValue());
        }
        if (expireDay > 0) {
            expireDay = expireDay * -1;
        }

        log.info("[BLOSSOM] 删除{}日前的编辑记录", Math.abs(expireDay));
        Date expireDate = DateUtils.offsetDay(DateUtils.date(), expireDay);
        LambdaQueryWrapper<ArticleLogEntity> where = new LambdaQueryWrapper<>();
        where.lt(ArticleLogEntity::getCreTime, DateUtils.toYMD(expireDate));
        baseMapper.delete(where);
    }

}
