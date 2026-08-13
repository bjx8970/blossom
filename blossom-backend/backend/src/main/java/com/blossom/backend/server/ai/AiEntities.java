package com.blossom.backend.server.ai;

import lombok.Data;

import java.util.Date;

/**
 * AI 接入所需的持久化记录。单独放置，避免把设备令牌秘密混入普通响应对象。
 */
public final class AiEntities {

    private AiEntities() {
    }

    @Data
    public static class DeviceToken {
        private Long id;
        private Long userId;
        private String deviceId;
        private String name;
        private String tokenPrefix;
        private String tokenHash;
        private String scopes;
        private Date expireTime;
        private Date revokedTime;
        private Date lastUsedTime;
        private Date creTime;
        private Date updTime;
    }

    @Data
    public static class TokenPrincipal extends DeviceToken {
        private String username;
        private Integer userType;
        private Long userDelTime;
    }

    @Data
    public static class Idempotency {
        private Long id;
        private Long userId;
        private Long tokenId;
        private String operation;
        private String idempotencyKey;
        private String requestHash;
        private Long resourceId;
        private Date creTime;
    }

    @Data
    public static class Audit {
        private Long id;
        private Long userId;
        private Long tokenId;
        private String action;
        private String resourceType;
        private Long resourceId;
        private String requestId;
        private Long beforeRevision;
        private Long afterRevision;
        private String result;
        private String errorCode;
        private String ip;
    }

    @Data
    public static class ArticleMutation {
        private Long id;
        private Long userId;
        private Long expectedRevision;
        private Long folderId;
        private Integer sort;
        private String title;
        private String tags;
        private String markdown;
        private String html;
        private String toc;
        private Integer words;
        private Date markdownUpdatedAt;
    }
}
