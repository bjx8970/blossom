package com.blossom.backend.server.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.util.Date;
import java.util.List;

/**
 * 外部 AI API 的稳定传输模型。字段使用完整名称，不复用编辑器内部的短字段协议。
 */
public final class AiModels {

    private AiModels() {
    }

    @Data
    public static class TokenIssueReq {
        @Size(max = 64, message = "deviceId 最长 64 个字符")
        private String deviceId;
        @NotBlank(message = "设备名称不能为空")
        @Size(max = 100, message = "设备名称最长 100 个字符")
        @JsonAlias("name")
        private String deviceName;
        @NotEmpty(message = "scopes 不能为空")
        private List<String> scopes;
        @Min(value = 1, message = "有效期至少 1 天")
        @Max(value = 90, message = "有效期最多 90 天")
        private Integer expiresInDays;
    }

    @Data
    public static class TokenRotateReq {
        private List<String> scopes;
        @Min(value = 1, message = "有效期至少 1 天")
        @Max(value = 90, message = "有效期最多 90 天")
        private Integer expiresInDays;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TokenRes {
        private Long id;
        private String deviceId;
        private String deviceName;
        private String tokenPrefix;
        private List<String> scopes;
        private Date expireTime;
        private Date revokedTime;
        private Date lastUsedTime;
        private Date creTime;
        /** 仅在签发或轮换成功的这一次响应中出现。 */
        private String token;
    }

    @Data
    public static class FolderRes {
        private Long id;
        private Long parentId;
        private String name;
        private String path;
        private Integer sort;
    }

    @Data
    public static class ArticleSummaryRes {
        private Long id;
        private Long folderId;
        private String title;
        private List<String> tags;
        private Integer words;
        private Integer contentVersion;
        private Long revision;
        private Date createdAt;
        private Date updatedAt;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String snippet;
    }

    @Data
    public static class ArticleDetailRes extends ArticleSummaryRes {
        private String markdown;
    }

    @Data
    public static class ArticleCursorRes {
        private List<ArticleSummaryRes> items;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String nextCursor;
    }

    @Data
    public static class ArticleCreateReq {
        @NotBlank(message = "clientRequestId 不能为空")
        @Size(max = 80, message = "clientRequestId 最长 80 个字符")
        private String clientRequestId;
        @NotBlank(message = "标题不能为空")
        @Size(max = 255, message = "标题最长 255 个字符")
        private String title;
        @NotNull(message = "markdown 不能为空")
        @Size(max = 2097152, message = "markdown 字符数不能超过 2MiB")
        private String markdown;
        @NotNull(message = "folderId 不能为空，根目录请显式传 0")
        private Long folderId;
        @Size(max = 50, message = "标签数量不能超过 50")
        private List<String> tags;
    }

    @Data
    public static class ArticleUpdateReq {
        @NotBlank(message = "clientRequestId 不能为空")
        @Size(max = 80, message = "clientRequestId 最长 80 个字符")
        private String clientRequestId;
        @NotNull(message = "expectedRevision 不能为空")
        @Min(value = 0, message = "expectedRevision 不能小于 0")
        private Long expectedRevision;
        @Size(max = 255, message = "标题最长 255 个字符")
        private String title;
        private Long folderId;
        @Size(max = 50, message = "标签数量不能超过 50")
        private List<String> tags;
        @Size(max = 2097152, message = "markdown 字符数不能超过 2MiB")
        private String markdown;
        @Valid
        @Size(max = 100, message = "单次最多 100 个精确编辑")
        private List<ExactEdit> edits;
    }

    @Data
    public static class ExactEdit {
        @NotEmpty(message = "oldText 不能为空")
        @Size(max = 1048576, message = "oldText 过长")
        private String oldText;
        @NotNull(message = "newText 不能为空")
        @Size(max = 1048576, message = "newText 过长")
        private String newText;
    }
}
