package com.dealtrace.auth;

import com.dealtrace.account.entity.Account;
import com.dealtrace.account.entity.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 签发与解析（HS256）。
 *
 * <p>Claims：{@code sub} = account.id（字符串）；{@code email}；{@code role}；{@code iat}；{@code exp = now + ttlHours}。
 * Secret 来自部署配置 {@code dealtrace.jwt.secret}，TTL 来自 {@code dealtrace.jwt.ttl-hours}（默认 16）。
 *
 * <p>token 不携带 {@code status}：账号停用语义由 {@code JwtAuthenticationFilter} 每请求实时查 DB 决定（design D2）。
 */
@Service
public class JwtService {

    private final SecretKey signingKey;
    private final Duration ttl;

    public JwtService(@Value("${dealtrace.jwt.secret}") String secret,
                      @Value("${dealtrace.jwt.ttl-hours:16}") long ttlHours) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                "dealtrace.jwt.secret 必须至少 32 字节（HS256 要求 256 bit）");
        }
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = Duration.ofHours(ttlHours);
    }

    public String generateToken(Account account) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(String.valueOf(account.getId()))
            .claim("email", account.getEmail())
            .claim("role", account.getRole().name())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(ttl)))
            .signWith(signingKey, Jwts.SIG.HS256)
            .compact();
    }

    public ParsedToken parse(String token) {
        Claims claims = Jwts.parser()
            .verifyWith(signingKey)
            .build()
            .parseSignedClaims(token)
            .getPayload();
        Long accountId = Long.valueOf(claims.getSubject());
        String email = claims.get("email", String.class);
        Role role = Role.valueOf(claims.get("role", String.class));
        return new ParsedToken(accountId, email, role);
    }

    public record ParsedToken(Long accountId, String email, Role role) {
    }
}
