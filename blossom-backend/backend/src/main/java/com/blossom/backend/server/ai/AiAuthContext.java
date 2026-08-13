package com.blossom.backend.server.ai;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Set;

/** 请求级 AI 设备身份。只保存数据库主键和范围，不保存原始 token。 */
public final class AiAuthContext {

    private static final ThreadLocal<Identity> CONTEXT = new ThreadLocal<>();

    private AiAuthContext() {
    }

    public static void set(Identity identity) {
        CONTEXT.set(identity);
    }

    public static Identity get() {
        return CONTEXT.get();
    }

    public static Long tokenId() {
        return CONTEXT.get() == null ? null : CONTEXT.get().getTokenId();
    }

    public static String requestId() {
        return CONTEXT.get() == null ? "" : CONTEXT.get().getRequestId();
    }

    public static void clear() {
        CONTEXT.remove();
    }

    @Data
    @AllArgsConstructor
    public static class Identity {
        private Long tokenId;
        private String deviceId;
        private Set<String> scopes;
        private String requestId;
    }
}
