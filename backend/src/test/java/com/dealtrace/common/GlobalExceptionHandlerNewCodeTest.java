package com.dealtrace.common;

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
 * lead-ownership design D3：两个新错误码经统一信封的 HTTP 状态映射。
 * <ul>
 *   <li>{@code LEAD_ALREADY_CLAIMED} → HTTP 409（并发抢占语义）</li>
 *   <li>{@code LEAD_ENDED_READONLY} → HTTP 400（与 DUPLICATE_* 一族对齐）</li>
 * </ul>
 * 过滤器关闭（addFilters=false）以便直达 TestThrowController 抛错端点。
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class GlobalExceptionHandlerNewCodeTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void leadAlreadyClaimed_mapsTo409WithCode() throws Exception {
        mockMvc.perform(get("/test/lead-already-claimed"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("LEAD_ALREADY_CLAIMED"))
            .andExpect(jsonPath("$.message").value("该线索已被认领"));
    }

    @Test
    void leadEndedReadonly_mapsTo400WithCode() throws Exception {
        mockMvc.perform(get("/test/lead-ended-readonly"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("LEAD_ENDED_READONLY"))
            .andExpect(jsonPath("$.message").value("线索已结束，不可操作"));
    }
}
