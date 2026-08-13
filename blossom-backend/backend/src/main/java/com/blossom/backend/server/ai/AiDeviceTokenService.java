package com.blossom.backend.server.ai;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.blossom.backend.base.param.ParamEnum;
import com.blossom.backend.base.param.ParamService;
import com.blossom.backend.base.param.pojo.ParamEntity;
import com.blossom.backend.base.auth.exception.AuthRCode;
import com.blossom.common.base.exception.XzException;
import com.blossom.common.base.exception.XzException404;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

@Service
public class AiDeviceTokenService {

    public static final String READ_SCOPE = "articles:read";
    public static final String WRITE_SCOPE = "articles:write";
    private static final String TOKEN_PREFIX = "bls_ai_";
    private static final Pattern DEVICE_ID = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int REQUESTS_PER_MINUTE = 60;
    private static final int DEFAULT_EXPIRY_DAYS = 90;
    private static final int MAX_EXPIRY_DAYS = 90;

    private final AiMapper mapper;
    private final ParamService paramService;
    private final ConcurrentHashMap<Long, RateWindow> rateWindows = new ConcurrentHashMap<>();
    private final AtomicLong rateChecks = new AtomicLong();

    @Value("${project.ai.token-pepper:}")
    private String configuredPepper;

    public AiDeviceTokenService(AiMapper mapper, ParamService paramService) {
        this.mapper = mapper;
        this.paramService = paramService;
    }

    public List<AiModels.TokenRes> list(Long userId) {
        List<AiModels.TokenRes> result = new ArrayList<>();
        for (AiEntities.DeviceToken token : mapper.listDeviceTokens(userId)) {
            result.add(toResponse(token, null));
        }
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public AiModels.TokenRes issue(Long userId, AiModels.TokenIssueReq req) {
        String deviceId = StrUtil.isBlank(req.getDeviceId())
                ? UUID.randomUUID().toString().replace("-", "") : req.getDeviceId().trim();
        validateDeviceId(deviceId);
        Set<String> scopes = normalizeScopes(req.getScopes());
        int days = normalizeExpiryDays(req.getExpiresInDays());

        AiEntities.DeviceToken token = mapper.selectDeviceTokenByDevice(deviceId, userId);
        boolean insert = token == null;
        if (insert) {
            token = new AiEntities.DeviceToken();
            token.setUserId(userId);
            token.setDeviceId(deviceId);
        }
        token.setName(req.getDeviceName().trim());
        String raw = newRawToken(userId, deviceId);
        token.setTokenPrefix(raw.substring(0, Math.min(24, raw.length())));
        token.setTokenHash(hmac(raw));
        token.setScopes(String.join(",", scopes));
        token.setExpireTime(new Date(System.currentTimeMillis() + days * 86_400_000L));
        try {
            if (insert) {
                if (mapper.insertDeviceToken(token) != 1) {
                    throw new XzException("AI-TOKEN-ISSUE-FAILED", "设备令牌签发失败");
                }
            } else {
                XzException404.throwBy(mapper.rotateDeviceToken(token) != 1, "设备令牌不存在或无权轮换");
            }
        } catch (DuplicateKeyException e) {
            throw new XzException("AI-DEVICE-ID-CONFLICT", "deviceId 已被占用，请生成新的随机 deviceId");
        }
        return toResponse(token, raw);
    }

    @Transactional(rollbackFor = Exception.class)
    public AiModels.TokenRes rotate(Long userId, Long id, AiModels.TokenRotateReq req) {
        AiEntities.DeviceToken current = mapper.selectDeviceToken(id, userId);
        XzException404.throwBy(current == null, "设备令牌不存在或无权轮换");
        if (current.getRevokedTime() != null) {
            // 撤销是用户的显式安全决定，后台自动轮换绝不能把它重新激活。
            throw new XzException(AuthRCode.INVALID_TOKEN);
        }
        Set<String> scopes = req == null || CollUtil.isEmpty(req.getScopes())
                ? parseScopes(current.getScopes()) : normalizeScopes(req.getScopes());
        int days = normalizeExpiryDays(req == null ? null : req.getExpiresInDays());
        String raw = newRawToken(userId, current.getDeviceId());
        current.setTokenPrefix(raw.substring(0, Math.min(24, raw.length())));
        current.setTokenHash(hmac(raw));
        current.setScopes(String.join(",", scopes));
        current.setExpireTime(new Date(System.currentTimeMillis() + days * 86_400_000L));
        XzException404.throwBy(mapper.rotateDeviceToken(current) != 1, "设备令牌不存在或无权轮换");
        return toResponse(current, raw);
    }

    public void revoke(Long userId, Long id) {
        XzException404.throwBy(mapper.revokeDeviceToken(id, userId) != 1, "设备令牌不存在或无权撤销");
    }

    public AiEntities.TokenPrincipal authenticate(String rawToken, boolean write) {
        if (rawToken == null || rawToken.length() > 160 || !rawToken.startsWith(TOKEN_PREFIX)) {
            throw new XzException(AuthRCode.INVALID_TOKEN);
        }
        int userDot = rawToken.indexOf('.', TOKEN_PREFIX.length());
        int deviceDot = userDot < 0 ? -1 : rawToken.indexOf('.', userDot + 1);
        if (userDot < 0 || deviceDot < 0) {
            throw new XzException(AuthRCode.INVALID_TOKEN);
        }
        Long userId;
        try {
            userId = Long.parseLong(rawToken.substring(TOKEN_PREFIX.length(), userDot));
        } catch (NumberFormatException e) {
            throw new XzException(AuthRCode.INVALID_TOKEN);
        }
        String deviceId = rawToken.substring(userDot + 1, deviceDot);
        // 认证入口不向调用方暴露 token 内部字段的校验差异，所有格式错误统一视为无效令牌。
        if (!DEVICE_ID.matcher(deviceId).matches()) {
            throw new XzException(AuthRCode.INVALID_TOKEN);
        }
        AiEntities.TokenPrincipal principal = mapper.selectPrincipalByDevice(deviceId, userId);
        if (principal == null || principal.getRevokedTime() != null
                || principal.getExpireTime() == null || !principal.getExpireTime().after(new Date())
                || principal.getUserDelTime() == null || principal.getUserDelTime() != 0L) {
            throw new XzException(AuthRCode.INVALID_TOKEN);
        }
        byte[] actual = hexToBytes(hmac(rawToken));
        byte[] expected = hexToBytes(principal.getTokenHash());
        if (!MessageDigest.isEqual(actual, expected)) {
            throw new XzException(AuthRCode.INVALID_TOKEN);
        }
        String required = write ? WRITE_SCOPE : READ_SCOPE;
        if (!parseScopes(principal.getScopes()).contains(required)) {
            throw new XzException(AuthRCode.PERMISSION_DENIED);
        }
        enforceRateLimit(principal.getId());
        mapper.touchDeviceToken(principal.getId());
        return principal;
    }

    public Set<String> parseScopes(String value) {
        if (StrUtil.isBlank(value)) {
            return Collections.emptySet();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String scope : value.split(",")) {
            if (!scope.trim().isEmpty()) {
                result.add(scope.trim());
            }
        }
        return result;
    }

    private Set<String> normalizeScopes(List<String> values) {
        Set<String> result = new LinkedHashSet<>();
        for (String scope : values) {
            if (!READ_SCOPE.equals(scope) && !WRITE_SCOPE.equals(scope)) {
                throw new XzException("AI-SCOPE-INVALID", "scope 仅支持 articles:read 或 articles:write");
            }
            result.add(scope);
        }
        if (result.isEmpty()) {
            throw new XzException("AI-SCOPE-INVALID", "scopes 不能为空");
        }
        return result;
    }

    private AiModels.TokenRes toResponse(AiEntities.DeviceToken token, String raw) {
        AiModels.TokenRes result = new AiModels.TokenRes();
        result.setId(token.getId());
        result.setDeviceId(token.getDeviceId());
        result.setDeviceName(token.getName());
        result.setTokenPrefix(token.getTokenPrefix());
        result.setScopes(new ArrayList<>(parseScopes(token.getScopes())));
        result.setExpireTime(token.getExpireTime());
        result.setRevokedTime(token.getRevokedTime());
        result.setLastUsedTime(token.getLastUsedTime());
        result.setCreTime(token.getCreTime());
        result.setToken(raw);
        return result;
    }

    private String newRawToken(Long userId, String deviceId) {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return TOKEN_PREFIX + userId + "." + deviceId + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hmac(String raw) {
        String pepper = configuredPepper;
        if (StrUtil.isBlank(pepper)) {
            ParamEntity param = paramService.getValue(ParamEnum.AI_TOKEN_PEPPER);
            pepper = param == null ? null : param.getParamValue();
        }
        if (pepper == null || pepper.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new XzException("AI-PEPPER-MISSING", "请配置至少 32 字节的 project.ai.token-pepper");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return toHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new XzException("AI-TOKEN-HASH-FAILED", "设备令牌摘要计算失败");
        }
    }

    private void validateDeviceId(String deviceId) {
        if (deviceId == null || !DEVICE_ID.matcher(deviceId).matches()) {
            throw new XzException("AI-DEVICE-ID-INVALID", "deviceId 仅允许 8-64 位字母、数字、下划线或短横线");
        }
    }

    private int normalizeExpiryDays(Integer value) {
        if (value == null) {
            return DEFAULT_EXPIRY_DAYS;
        }
        if (value < 1 || value > MAX_EXPIRY_DAYS) {
            throw new XzException("AI-TOKEN-EXPIRY-INVALID", "设备令牌有效期必须在 1-90 天之间");
        }
        return value;
    }

    private String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(String.format("%02x", value & 0xff));
        }
        return out.toString();
    }

    private byte[] hexToBytes(String value) {
        if (value == null || value.length() != 64) {
            return new byte[0];
        }
        byte[] bytes = new byte[value.length() / 2];
        try {
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
            }
            return bytes;
        } catch (NumberFormatException ignored) {
            return new byte[0];
        }
    }

    private void enforceRateLimit(Long tokenId) {
        long now = System.currentTimeMillis();
        RateWindow window = rateWindows.compute(tokenId, (id, current) -> {
            if (current == null || now - current.startedAt >= 60_000L) {
                return new RateWindow(now, 1);
            }
            current.count++;
            return current;
        });
        if (window.count > REQUESTS_PER_MINUTE) {
            throw new XzException("AI-RATE-LIMIT", "设备令牌请求频率超过每分钟 60 次");
        }
        if ((rateChecks.incrementAndGet() & 255L) == 0L) {
            rateWindows.entrySet().removeIf(entry -> now - entry.getValue().startedAt >= 120_000L);
        }
    }

    private static class RateWindow {
        private final long startedAt;
        private int count;

        private RateWindow(long startedAt, int count) {
            this.startedAt = startedAt;
            this.count = count;
        }
    }
}
