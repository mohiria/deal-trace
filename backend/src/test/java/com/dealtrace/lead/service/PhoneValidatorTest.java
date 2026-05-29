package com.dealtrace.lead.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec R3 + design D7：中国大陆 11 位手机号 + 常见座机格式校验。
 * trim 在 isValid 内部进行（与 UsciValidator.normalize 模式一致）。
 */
class PhoneValidatorTest {

    @Test
    void isValid_mobileWithValidPrefix_true() {
        assertThat(PhoneValidator.isValid("13812345678")).isTrue();
        assertThat(PhoneValidator.isValid("19987654321")).isTrue();
        assertThat(PhoneValidator.isValid("15012345678")).isTrue();
    }

    @Test
    void isValid_mobileFirstDigitNotOne_false() {
        assertThat(PhoneValidator.isValid("23812345678")).isFalse();
    }

    @Test
    void isValid_mobileSecondDigitOutOfRange_false() {
        // 第 2 位需在 3-9
        assertThat(PhoneValidator.isValid("11812345678")).isFalse();
        assertThat(PhoneValidator.isValid("12812345678")).isFalse();
    }

    @Test
    void isValid_mobileWithLetters_false() {
        assertThat(PhoneValidator.isValid("138ABCD5678")).isFalse();
    }

    @Test
    void isValid_landlineWithAreaCode_true() {
        assertThat(PhoneValidator.isValid("010-12345678")).isTrue();
        assertThat(PhoneValidator.isValid("0571-12345678")).isTrue();
    }

    @Test
    void isValid_landlineWithExtension_true() {
        assertThat(PhoneValidator.isValid("0571-12345678-123")).isTrue();
        assertThat(PhoneValidator.isValid("010-12345678-1")).isTrue();
    }

    @Test
    void isValid_landlineWithoutAreaCode_true() {
        assertThat(PhoneValidator.isValid("12345678")).isTrue();
        assertThat(PhoneValidator.isValid("1234567")).isTrue();
    }

    @Test
    void isValid_trimsBeforeMatching() {
        assertThat(PhoneValidator.isValid("  13812345678  ")).isTrue();
        assertThat(PhoneValidator.isValid("  010-12345678  ")).isTrue();
    }

    @Test
    void isValid_null_false() {
        assertThat(PhoneValidator.isValid(null)).isFalse();
    }

    @Test
    void isValid_blank_false() {
        assertThat(PhoneValidator.isValid("")).isFalse();
        assertThat(PhoneValidator.isValid("   ")).isFalse();
    }

    @Test
    void isValid_foreignFormat_false() {
        assertThat(PhoneValidator.isValid("+1-555-1234")).isFalse();
        assertThat(PhoneValidator.isValid("abc")).isFalse();
        assertThat(PhoneValidator.isValid("123")).isFalse(); // 太短
    }
}
