package com.dealtrace.common.testsupport;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅在 test profile 加载的故意抛错端点，用于驱动 GlobalExceptionHandler 的行为契约测试。
 * 不属于 platform-foundation spec，纯粹是测试 fixture。
 */
@RestController
@RequestMapping("/test")
@Profile("test")
public class TestThrowController {

    @GetMapping("/internal-error")
    public String internalError() {
        throw new RuntimeException("intentional runtime failure for INTERNAL_ERROR test");
    }

    @PostMapping("/validation-error")
    public String validationError(@RequestBody @Valid Payload payload) {
        return payload.value();
    }

    public record Payload(@NotBlank String value) {
    }
}
