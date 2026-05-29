package com.dealtrace.lead.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * tech-arch §9.4 联系电话脱敏分级：
 * <ol>
 *   <li>11 位手机号 → 前 3 + {@code ****} + 后 4（`138****5678`）</li>
 *   <li>非 11 位且 ≥8 位 → 前 3 + {@code ****} + 后 4</li>
 *   <li>&lt;8 位 → 仅展示后 2 位，其余 {@code *}</li>
 * </ol>
 */
class PhoneMaskerTest {

    @Test
    void mask_mobile11Digits_frontThreeBackFour() {
        assertThat(PhoneMasker.mask("13812345678")).isEqualTo("138****5678");
        assertThat(PhoneMasker.mask("19987654321")).isEqualTo("199****4321");
    }

    @Test
    void mask_landlineWithAreaCode_frontThreeBackFour() {
        // 12 位（含连字符），落入"非 11 位且 ≥8 位"分支
        assertThat(PhoneMasker.mask("010-12345678")).isEqualTo("010****5678");
        assertThat(PhoneMasker.mask("0571-12345678")).isEqualTo("057****5678");
    }

    @Test
    void mask_eightDigits_frontThreeBackFour() {
        // 长度恰为 8（边界），走 ≥8 分支
        assertThat(PhoneMasker.mask("12345678")).isEqualTo("123****5678");
    }

    @Test
    void mask_shorterThanEight_onlyLastTwoVisible() {
        assertThat(PhoneMasker.mask("1234567")).isEqualTo("*****67");
        assertThat(PhoneMasker.mask("123")).isEqualTo("*23");
        assertThat(PhoneMasker.mask("12")).isEqualTo("12");
    }

    @Test
    void mask_trimsBeforeMasking() {
        assertThat(PhoneMasker.mask("  13812345678  ")).isEqualTo("138****5678");
    }

    @Test
    void mask_nullOrBlank_returnedAsIs() {
        assertThat(PhoneMasker.mask(null)).isNull();
        assertThat(PhoneMasker.mask("")).isEqualTo("");
        assertThat(PhoneMasker.mask("   ")).isEqualTo("");
    }
}
