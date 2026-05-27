package com.dealtrace.common;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void internalErrorReturnsEnvelopeWithoutLeakingDetails() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/internal-error"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.message").exists())
            .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
            .as("响应体不得泄漏异常类名、堆栈帧或 SQL 等敏感细节")
            .doesNotContain("RuntimeException")
            .doesNotContain("java.lang")
            .doesNotContain("at com.dealtrace")
            .doesNotContainIgnoringCase("stacktrace")
            .doesNotContainIgnoringCase("\"trace\"");
    }

    @Test
    void validationErrorReturnsBadRequestEnvelope() throws Exception {
        mockMvc.perform(post("/test/validation-error")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").exists());
    }
}
