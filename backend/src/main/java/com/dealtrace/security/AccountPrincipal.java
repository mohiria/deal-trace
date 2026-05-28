package com.dealtrace.security;

import com.dealtrace.account.entity.Role;

/**
 * 当前认证用户身份载体；通过 {@code @AuthenticationPrincipal AccountPrincipal} 在 controller 注入。
 * 仅承载 id / email / role，不存密码哈希。
 */
public record AccountPrincipal(Long id, String email, Role role) {
}
