package com.dealtrace.security;

import com.dealtrace.common.ApiResponse;
import com.dealtrace.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 未认证或认证失败的统一入口：写出 {@link ApiResponse} 信封，HTTP 401。
 *
 * <p>auth-account 起：若 {@link AuthenticationException#getMessage()} 是"账号已停用"或"令牌无效"等
 * 业务白名单 message，则透传到响应 message；否则回退默认文案，避免泄漏 Spring Security 内部 message。
 */
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String DEFAULT_MESSAGE = "未认证或认证已过期";

    /**
     * 允许透传到响应 message 的业务文案白名单。保持显式集合是为了避免泄漏框架自带 message
     * （例如 "Full authentication is required to access this resource"）或 jjwt 解析细节。
     */
    private static final java.util.Set<String> ALLOWED_MESSAGES = java.util.Set.of(
        "账号已停用",
        "账号不存在",
        "令牌无效",
        "原密码错误"
    );

    private final ObjectMapper objectMapper;

    public JwtAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        String raw = authException != null ? authException.getMessage() : null;
        String message = (raw != null && ALLOWED_MESSAGES.contains(raw)) ? raw : DEFAULT_MESSAGE;

        ApiResponse<Void> body = ApiResponse.error(ErrorCode.UNAUTHORIZED, message);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
