package com.dealtrace.customer;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dealtrace.account.entity.Account;
import com.dealtrace.account.entity.AccountStatus;
import com.dealtrace.account.entity.Role;
import com.dealtrace.account.repository.AccountMapper;
import com.dealtrace.auth.JwtService;
import com.dealtrace.common.IntegrationTest;
import com.dealtrace.customer.entity.Customer;
import com.dealtrace.customer.repository.CustomerMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec R1 / R2 / R3 / R4（非并发） / R5 集成测试。
 *
 * <p>USCI 真实合法样本（design lightweight-test-design 预计算）：
 *   91110000123456789Q / 91110108551385082Q / 91440300083000123J
 */
@AutoConfigureMockMvc
class CustomerControllerCreateTest extends IntegrationTest {

    private static final String VALID_USCI_1 = "91110000123456789Q";
    private static final String VALID_USCI_2 = "91110108551385082Q";
    private static final String VALID_USCI_3 = "91440300083000123J";

    @Autowired private MockMvc mockMvc;
    @Autowired private AccountMapper accountMapper;
    @Autowired private CustomerMapper customerMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private Account admin;
    private Account sales;

    @BeforeEach
    void seed() {
        accountMapper.delete(null);
        admin = insertAccount("customer-test-admin@dealtrace.test", "Admin", Role.ADMIN);
        sales = insertAccount("customer-test-sales@dealtrace.test", "Sales", Role.SALES);
    }

    private Account insertAccount(String email, String name, Role role) {
        Account a = new Account();
        a.setEmail(email);
        a.setName(name);
        a.setRole(role);
        a.setStatus(AccountStatus.ENABLED);
        a.setPasswordHash(passwordEncoder.encode("p@ssw0rd"));
        LocalDateTime now = LocalDateTime.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        accountMapper.insert(a);
        return a;
    }

    private RequestBuilder createReq(String body, String token) {
        var rb = post("/customers")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
        if (token != null) {
            rb = rb.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return rb;
    }

    private String body(String name, String usci) {
        // 简陋的 JSON 拼接，避免引入 ObjectMapper 依赖。name 与 usci 不含双引号。
        return "{\"name\":\"" + name + "\",\"usci\":\"" + usci + "\"}";
    }

    // ---- spec R1 + R5 (Sales 创建) + R3 (归一化后落库) ----
    @Test
    void sales_createsCustomer_returnsViewAndPersistsNormalized() throws Exception {
        String token = jwtService.generateToken(sales);
        // 提交带空格小写 USCI，落库应为归一化后的大写无空格
        String rawUsci = "  " + VALID_USCI_1.toLowerCase() + "  ";

        mockMvc.perform(createReq(body("中国建筑设计研究院", rawUsci), token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.data.id").isNumber())
            .andExpect(jsonPath("$.data.name").value("中国建筑设计研究院"))
            .andExpect(jsonPath("$.data.usci").value(VALID_USCI_1))
            .andExpect(jsonPath("$.data.createdAt").isString())
            // R1：响应不含 lead 字段
            .andExpect(jsonPath("$.data.contactName").doesNotExist())
            .andExpect(jsonPath("$.data.contactPhone").doesNotExist())
            .andExpect(jsonPath("$.data.ownerSalesId").doesNotExist());

        Customer dbRow = customerMapper.selectOne(
            new QueryWrapper<Customer>().eq("usci", VALID_USCI_1));
        assertThat(dbRow).isNotNull();
        assertThat(dbRow.getUsci()).isEqualTo(VALID_USCI_1);
        assertThat(dbRow.getName()).isEqualTo("中国建筑设计研究院");
    }

    // ---- spec R5 (Admin 创建) ----
    @Test
    void admin_createsCustomer_success() throws Exception {
        String token = jwtService.generateToken(admin);
        mockMvc.perform(createReq(body("北京 ABC 公司", VALID_USCI_2), token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    // ---- spec R5 场景 3 (匿名 401) ----
    @Test
    void anonymous_rejected_with401() throws Exception {
        mockMvc.perform(createReq(body("匿名公司", VALID_USCI_1), null))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    // ---- spec R2 场景 1 (name 全空白拒绝) ----
    @Test
    void blankName_rejected() throws Exception {
        String token = jwtService.generateToken(sales);
        mockMvc.perform(createReq(body("   ", VALID_USCI_1), token))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("客户名称")));

        assertThat(customerMapper.selectCount(null)).isZero();
    }

    // ---- spec R3 场景 4 (usci 全空白拒绝) ----
    @Test
    void blankUsci_rejected() throws Exception {
        String token = jwtService.generateToken(sales);
        mockMvc.perform(createReq(body("X 公司", "   "), token))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertThat(customerMapper.selectCount(null)).isZero();
    }

    // ---- spec R3 场景 2 (长度错) ----
    @Test
    void usciWrongLength_rejected() throws Exception {
        String token = jwtService.generateToken(sales);
        mockMvc.perform(createReq(body("X 公司", "91110000123456789"), token)) // 17 位
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertThat(customerMapper.selectCount(null)).isZero();
    }

    // ---- spec R3 场景 3 (校验位错) ----
    @Test
    void usciWrongCheckDigit_rejected() throws Exception {
        String token = jwtService.generateToken(sales);
        // VALID_USCI_1 的末位 Q 改成 A → 校验位失败
        mockMvc.perform(createReq(body("X 公司", "91110000123456789A"), token))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertThat(customerMapper.selectCount(null)).isZero();
    }

    // ---- spec R4 场景 1 (USCI 重复，含小写覆盖) ----
    @Test
    void duplicateUsci_caseInsensitive_rejected() throws Exception {
        String token = jwtService.generateToken(sales);
        mockMvc.perform(createReq(body("公司 A", VALID_USCI_1), token))
            .andExpect(status().isOk());

        // 同 USCI 小写 + 不同 name → 应被 USCI 唯一性拦截
        mockMvc.perform(createReq(body("公司 B", VALID_USCI_1.toLowerCase()), token))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("DUPLICATE_CUSTOMER"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("USCI")));

        assertThat(customerMapper.selectCount(null)).isEqualTo(1L);
    }

    // ---- spec R2 场景 2 (name trim 后重复) ----
    @Test
    void duplicateNameAfterTrim_rejected() throws Exception {
        String token = jwtService.generateToken(sales);
        mockMvc.perform(createReq(body("中国建筑设计研究院", VALID_USCI_1), token))
            .andExpect(status().isOk());

        // 同 name（带空格） + 不同合法 USCI → 应被 name 唯一性拦截
        mockMvc.perform(createReq(body("  中国建筑设计研究院  ", VALID_USCI_2), token))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("DUPLICATE_CUSTOMER"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("名称")));

        assertThat(customerMapper.selectCount(null)).isEqualTo(1L);
    }

    // ---- spec R2 场景 3 (不同公司名都允许) ----
    @Test
    void differentNames_bothAllowed() throws Exception {
        String token = jwtService.generateToken(sales);
        mockMvc.perform(createReq(body("中国建筑设计研究院", VALID_USCI_1), token))
            .andExpect(status().isOk());
        mockMvc.perform(createReq(body("中建院", VALID_USCI_2), token))
            .andExpect(status().isOk());
        mockMvc.perform(createReq(body("另一家公司", VALID_USCI_3), token))
            .andExpect(status().isOk());

        assertThat(customerMapper.selectCount(null)).isEqualTo(3L);
    }
}
