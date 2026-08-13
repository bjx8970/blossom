package com.blossom.backend.server.ai;

import com.blossom.backend.server.article.draft.pojo.ArticleEntity;
import com.blossom.backend.server.folder.pojo.FolderEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiMapper {

    List<AiEntities.DeviceToken> listDeviceTokens(@Param("userId") Long userId);

    AiEntities.DeviceToken selectDeviceToken(@Param("id") Long id, @Param("userId") Long userId);

    AiEntities.DeviceToken selectDeviceTokenByDevice(@Param("deviceId") String deviceId,
                                                      @Param("userId") Long userId);

    AiEntities.TokenPrincipal selectPrincipalByDevice(@Param("deviceId") String deviceId,
                                                       @Param("userId") Long userId);

    int insertDeviceToken(AiEntities.DeviceToken token);

    int rotateDeviceToken(AiEntities.DeviceToken token);

    int revokeDeviceToken(@Param("id") Long id, @Param("userId") Long userId);

    int touchDeviceToken(@Param("id") Long id);

    List<FolderEntity> listFolders(@Param("userId") Long userId);

    int countOwnedFolder(@Param("id") Long id, @Param("userId") Long userId);

    List<ArticleEntity> listArticles(@Param("userId") Long userId,
                                     @Param("cursor") Long cursor,
                                     @Param("folderId") Long folderId,
                                     @Param("updatedAfter") java.util.Date updatedAfter,
                                     @Param("limit") int limit);

    List<ArticleEntity> searchArticles(@Param("userId") Long userId,
                                       @Param("query") String query,
                                       @Param("cursor") Long cursor,
                                       @Param("limit") int limit);

    ArticleEntity selectArticle(@Param("id") Long id, @Param("userId") Long userId);

    int selectMaxArticleSort(@Param("folderId") Long folderId, @Param("userId") Long userId);

    int updateArticle(AiEntities.ArticleMutation mutation);

    AiEntities.Idempotency selectIdempotency(@Param("tokenId") Long tokenId,
                                             @Param("operation") String operation,
                                             @Param("idempotencyKey") String idempotencyKey);

    int reserveIdempotency(AiEntities.Idempotency idempotency);

    int updateIdempotencyResource(@Param("id") Long id,
                                  @Param("tokenId") Long tokenId,
                                  @Param("resourceId") Long resourceId);

    int deleteExpiredIdempotency(@Param("tokenId") Long tokenId,
                                 @Param("operation") String operation,
                                 @Param("idempotencyKey") String idempotencyKey);

    int deleteAllExpiredIdempotency();

    int insertAudit(AiEntities.Audit audit);
}
