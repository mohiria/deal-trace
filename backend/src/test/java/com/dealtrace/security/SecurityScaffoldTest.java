package com.dealtrace.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证 platform-foundation R1 中 UNAUTHORIZED 分支：未携带有效凭证访问受保护端点时，
 * 必须返回 HTTP 401 + 统一 ApiResponse 信封 + 响应体不泄漏 stack。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityScaffoldTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unauthenticatedAccessReturnsUnifiedUnauthorizedEnvelope() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/protected"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.message").exists())
            .andExpect(jsonPath("$.data").doesNotExist())
            .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
            .as("401 响应体不得泄漏异常类名或堆栈帧")
            .doesNotContain("AuthenticationException")
            .doesNotContain("at com.dealtrace")
            .doesNotContainIgnoringCase("stacktrace")
            .doesNotContainIgnoringCase("\"trace\"");
    }
}
