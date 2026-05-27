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
 * 响应体不含异常类名 / 堆栈，保持与 GlobalExceptionHandler 一致的对外契约。
 */
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

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

        ApiResponse<Void> body = ApiResponse.error(ErrorCode.UNAUTHORIZED, "未认证或认证已过期");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
