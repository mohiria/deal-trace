package com.dealtrace.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 认证过滤器（骨架）。
 *
 * <p>scaffold-monorepo 仅落地框架：从 Authorization 头读取 Bearer token 字符串，
 * 不做签名校验、不构造 SecurityContext，直接 doFilter 放行。
 * 完整 token 解析与 SecurityContext 注入由 auth-account spec 实现。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            // 占位：token 解析与认证由 auth-account spec 落实
            String token = header.substring(BEARER_PREFIX.length());
            // intentionally no-op
            assert token != null;
        }
        filterChain.doFilter(request, response);
    }
}
