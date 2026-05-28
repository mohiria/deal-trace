package com.dealtrace.security;

import com.dealtrace.account.entity.Account;
import com.dealtrace.account.entity.AccountStatus;
import com.dealtrace.account.repository.AccountMapper;
import com.dealtrace.auth.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器。
 *
 * <p>流程（design D2）：
 * <ol>
 *   <li>读 Authorization 头；无 / 非 Bearer 直接 doFilter（由 SecurityConfig 决定是否需要认证）</li>
 *   <li>解析 token；非法 → 抛 BadCredentialsException 由 EntryPoint 写 401</li>
 *   <li>按 sub 实时查 account 表；不存在 / DISABLED 抛对应 AuthenticationException</li>
 *   <li>构造 AccountPrincipal + UsernamePasswordAuthenticationToken（权限 = ROLE_角色名）放入 SecurityContext</li>
 * </ol>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final AccountMapper accountMapper;
    private final AuthenticationEntryPoint authenticationEntryPoint;

    public JwtAuthenticationFilter(JwtService jwtService,
                                   AccountMapper accountMapper,
                                   JwtAuthenticationEntryPoint authenticationEntryPoint) {
        this.jwtService = jwtService;
        this.accountMapper = accountMapper;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            JwtService.ParsedToken parsed = parseOrThrow(token);
            Account account = accountMapper.selectById(parsed.accountId());
            if (account == null) {
                throw new BadCredentialsException("账号不存在");
            }
            if (account.getStatus() == AccountStatus.DISABLED) {
                throw new DisabledException("账号已停用");
            }
            AccountPrincipal principal = new AccountPrincipal(
                account.getId(), account.getEmail(), account.getRole()
            );
            UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + account.getRole().name()))
                );
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (AuthenticationException ex) {
            SecurityContextHolder.clearContext();
            authenticationEntryPoint.commence(request, response, ex);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private JwtService.ParsedToken parseOrThrow(String token) {
        try {
            return jwtService.parse(token);
        } catch (RuntimeException ex) {
            // 任何 jjwt 异常（签名 / 过期 / 解析）统一映射为"令牌无效"，
            // 不让技术细节（signature/expired/malformed/claim）泄漏到响应体。
            throw new BadCredentialsException("令牌无效");
        }
    }
}
