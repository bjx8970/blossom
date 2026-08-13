package com.blossom.backend.server.article.log;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blossom.backend.server.article.log.pojo.ArticleLogEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;


/**
 * 文章记录
 */
@Mapper
public interface ArticleLogMapper extends BaseMapper<ArticleLogEntity> {

    /**
     * 查询文章记录
     *
     * @param articleId 文章ID
     */
    List<ArticleLogEntity> listAll(@Param("articleId") Long articleId, @Param("userId") Long userId);

    /** 按日志 ID 查询正文，并校验其文章所有权。 */
    String selectContent(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 删除文章的记录
     *
     * @param articleIds 文章ID集合
     */
    Long delByIds(@Param("articleIds") List<Long> articleIds);
}
