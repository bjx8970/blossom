package com.blossom.backend.server.ai;

import cn.hutool.core.util.StrUtil;
import com.blossom.backend.base.auth.AuthConstant;
import com.blossom.backend.base.auth.AuthContext;
import com.blossom.backend.base.auth.filters.HttpFirewall;
import com.blossom.backend.base.auth.exception.AuthRCode;
import com.blossom.backend.base.auth.pojo.AccessToken;
import com.blossom.common.base.exception.XzAbstractException;
import com.blossom.common.base.pojo.R;
import com.blossom.common.base.util.json.JsonUtil;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ReadListener;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 仅认证 /api/ai/v1；管理接口仍由现有登录会话认证。 */
@Component
@Order(-102)
public class AiDeviceTokenFilter extends OncePerRequestFilter {

    private static final long MAX_AI_REQUEST_BYTES = 2_621_440L;
    private final AiDeviceTokenService tokenService;
    private final HttpFirewall firewall = new HttpFirewall();

    public AiDeviceTokenFilter(AiDeviceTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !(path.equals("/api/ai/v1") || path.startsWith("/api/ai/v1/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            firewall.wall(request);
            if (request.getContentLengthLong() > MAX_AI_REQUEST_BYTES) {
                throw new com.blossom.common.base.exception.XzException(
                        "AI-REQUEST-TOO-LARGE", "AI 请求体不能超过 2.5MiB");
            }
            String header = request.getHeader(AuthConstant.HEADER_AUTHORIZATION);
            if (StrUtil.isBlank(header) || !header.startsWith(AuthConstant.HEADER_TOKEN_PREFIX)
                    || header.length() <= AuthConstant.HEADER_TOKEN_PREFIX.length()) {
                throw new com.blossom.common.base.exception.XzException(AuthRCode.INVALID_TOKEN);
            }
            String raw = header.substring(AuthConstant.HEADER_TOKEN_PREFIX.length());
            boolean write = !("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()));
            AiEntities.TokenPrincipal principal = tokenService.authenticate(raw, write);
            String requestId = normalizeRequestId(request.getHeader("X-Request-Id"));

            AccessToken accessToken = new AccessToken();
            accessToken.setToken("");
            accessToken.setUserId(principal.getUserId());
            accessToken.setExpire(principal.getExpireTime().getTime());
            accessToken.setPermissions(new ArrayList<>(tokenService.parseScopes(principal.getScopes())));
            Map<String, String> metadata = new HashMap<>();
            metadata.put("username", principal.getUsername());
            metadata.put("type", String.valueOf(principal.getUserType()));
            metadata.put("authSource", "ai-device");
            accessToken.setMetadata(metadata);
            AuthContext.setContext(accessToken);
            AiAuthContext.set(new AiAuthContext.Identity(principal.getId(), principal.getDeviceId(),
                    tokenService.parseScopes(principal.getScopes()), requestId));
            request.setAttribute(AuthConstant.AI_DEVICE_AUTH_ATTRIBUTE_KEY, Boolean.TRUE);
            response.setHeader("X-Request-Id", requestId);
        } catch (Exception e) {
            String code = AuthRCode.INVALID_TOKEN.getCode();
            String message = "AI 设备认证失败";
            if (e instanceof XzAbstractException) {
                code = ((XzAbstractException) e).getCode();
                message = e.getMessage();
            }
            writeFault(response, code, message);
            AiAuthContext.clear();
            AuthContext.removeContext();
            return;
        }
        try {
            chain.doFilter(new LimitedRequest(request, MAX_AI_REQUEST_BYTES), response);
        } catch (Exception e) {
            if (isRequestTooLarge(e)) {
                if (!response.isCommitted()) {
                    response.resetBuffer();
                    writeFault(response, "AI-REQUEST-TOO-LARGE", "AI 请求体不能超过 2.5MiB");
                }
                return;
            }
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            if (e instanceof ServletException) {
                throw (ServletException) e;
            }
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new ServletException(e);
        } finally {
            AiAuthContext.clear();
            AuthContext.removeContext();
        }
    }

    private String normalizeRequestId(String value) {
        if (value != null && value.length() <= 80 && value.matches("[A-Za-z0-9._:-]+")) {
            return value;
        }
        return UUID.randomUUID().toString();
    }

    private void writeFault(HttpServletResponse response, String code, String message) throws IOException {
        response.setStatus(200);
        response.setContentType("application/json;charset=utf-8");
        response.getWriter().write(JsonUtil.toJson(R.fault(code, message)));
    }

    private boolean isRequestTooLarge(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof RequestTooLargeException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static class LimitedRequest extends HttpServletRequestWrapper {
        private final long limit;

        private LimitedRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new LimitedServletInputStream(((HttpServletRequest) getRequest()).getInputStream(), limit);
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static class LimitedServletInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long limit;
        private long read;

        private LimitedServletInputStream(ServletInputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                checkLimit(1);
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = delegate.read(bytes, offset, length);
            if (count > 0) {
                checkLimit(count);
            }
            return count;
        }

        private void checkLimit(int count) throws RequestTooLargeException {
            read += count;
            if (read > limit) {
                throw new RequestTooLargeException();
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    private static class RequestTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;
    }
}
