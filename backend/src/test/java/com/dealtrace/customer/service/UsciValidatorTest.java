package com.dealtrace.customer.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec R3 + design D3：GB 32100-2015 USCI 校验位 + 归一化（trim+upper）的纯单元测试。
 *
 * <p>无 Spring 上下文，直接对静态方法断言。三个真实合法 USCI 样本由 design 的
 * lightweight-test-design.md 用 GB32100 校验位算法预计算得出。
 */
class UsciValidatorTest {

    private static final String VALID_1 = "91110000123456789Q";
    private static final String VALID_2 = "91110108551385082Q";
    private static final String VALID_3 = "91440300083000123J";

    @Test
    void normalize_trimsAndUppers() {
        assertThat(UsciValidator.normalize("  91110000123456789q  "))
            .isEqualTo("91110000123456789Q");
    }

    @Test
    void normalize_alreadyNormalized_isIdentity() {
        assertThat(UsciValidator.normalize(VALID_1)).isEqualTo(VALID_1);
    }

    @Test
    void normalize_null_returnsNull() {
        assertThat(UsciValidator.normalize(null)).isNull();
    }

    @Test
    void normalize_blankString_returnsEmpty() {
        assertThat(UsciValidator.normalize("   ")).isEqualTo("");
    }

    @Test
    void isValid_realSamples_true() {
        assertThat(UsciValidator.isValid(VALID_1)).as("sample 1").isTrue();
        assertThat(UsciValidator.isValid(VALID_2)).as("sample 2").isTrue();
        assertThat(UsciValidator.isValid(VALID_3)).as("sample 3").isTrue();
    }

    @Test
    void isValid_null_false() {
        assertThat(UsciValidator.isValid(null)).isFalse();
    }

    @Test
    void isValid_wrongLength_false() {
        assertThat(UsciValidator.isValid("9111000012345678")).as("16 chars").isFalse();
        assertThat(UsciValidator.isValid("91110000123456789")).as("17 chars").isFalse();
        assertThat(UsciValidator.isValid("91110000123456789QX")).as("19 chars").isFalse();
    }

    @Test
    void isValid_charsetContainsForbidden_false() {
        // GB 32100-2015 排除 I / O / S / V / Z 五个易混字符
        assertThat(UsciValidator.isValid("9111000012345678IQ")).as("contains I").isFalse();
        assertThat(UsciValidator.isValid("9111000012345678OQ")).as("contains O").isFalse();
        assertThat(UsciValidator.isValid("9111000012345678SQ")).as("contains S").isFalse();
    }

    @Test
    void isValid_wrongCheckDigit_false() {
        // 合法样本末位改成另一个合法字符 → 校验位不再对得上
        assertThat(UsciValidator.isValid("91110000123456789A")).isFalse();
        assertThat(UsciValidator.isValid("91110108551385082B")).isFalse();
        assertThat(UsciValidator.isValid("91440300083000123K")).isFalse();
    }

    @Test
    void isValid_assumesAlreadyNormalized_lowercaseFails() {
        // 契约：isValid 入参必须先 normalize；传入小写不被宽容（charset 索引会失败）
        assertThat(UsciValidator.isValid("91110000123456789q")).isFalse();
    }
}
