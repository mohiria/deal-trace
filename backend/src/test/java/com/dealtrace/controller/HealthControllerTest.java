package com.dealtrace.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * platform-foundation R3：/health 端点匿名可访问，返回统一 ApiResponse 信封 + 存活字段。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anonymousGetHealthReturnsSuccessEnvelopeWithStatusUp() throws Exception {
        mockMvc.perform(get("/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.message").exists())
            .andExpect(jsonPath("$.data").exists())
            .andExpect(jsonPath("$.data.status").value("UP"));
    }
}
