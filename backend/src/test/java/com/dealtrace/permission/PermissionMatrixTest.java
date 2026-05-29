package com.dealtrace.permission;

import com.dealtrace.account.entity.Account;
import com.dealtrace.account.entity.AccountStatus;
import com.dealtrace.account.entity.Role;
import com.dealtrace.account.repository.AccountMapper;
import com.dealtrace.auth.JwtService;
import com.dealtrace.common.IntegrationTest;
import com.dealtrace.customer.entity.Customer;
import com.dealtrace.customer.repository.CustomerMapper;
import com.dealtrace.lead.entity.BusinessType;
import com.dealtrace.lead.entity.Lead;
import com.dealtrace.lead.entity.LeadStage;
import com.dealtrace.lead.repository.LeadMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 授权矩阵横切回归（spec permission INV-1..4）。
 *
 * <p>聚合 altitude 断言整面一致性，不重述各 capability 单端点细节（design D1/D5）：
 * <ul>
 *   <li>INV-1 默认拒绝：自动发现本项目全部端点，非 permitAll 白名单者匿名→401。</li>
 *   <li>INV-2 角色专属：ADMIN 专属端点 Sales→403；SALES 专属端点 Admin→403。</li>
 *   <li>INV-3 停用 Sales 聚合：不可登录 / 认领 / 被分配 / 被转移。</li>
 *   <li>INV-4 后端强校验：绕前端直达的越权请求仍被后端按矩阵拒绝。</li>
 * </ul>
 */
@AutoConfigureMockMvc
class PermissionMatrixTest extends IntegrationTest {

    private static final String VALID_USCI = "91110000123456789Q";
    private static final String PASSWORD = "p@ssw0rd";

    /** permitAll 白名单（method + 路径模板）；从匿名 401 扫描中排除。 */
    private static final Set<String> PERMIT_ALL = Set.of("GET /health", "POST /auth/login");

    /** ADMIN 专属端点（Sales→403）。路径含 {id} 用 1 占位（403 发生在授权层，先于业务）。 */
    private static final List<Endpoint> ADMIN_ONLY = List.of(
        new Endpoint(HttpMethod.GET, "/leads"),
        new Endpoint(HttpMethod.POST, "/leads/1/assign"),
        new Endpoint(HttpMethod.POST, "/leads/1/recall"),
        new Endpoint(HttpMethod.POST, "/leads/1/transfer"),
        new Endpoint(HttpMethod.POST, "/admin/accounts"),
        new Endpoint(HttpMethod.GET, "/admin/accounts"),
        new Endpoint(HttpMethod.PATCH, "/admin/accounts/1/status")
    );

    /** SALES 专属端点（Admin→403）。 */
    private static final List<Endpoint> SALES_ONLY = List.of(
        new Endpoint(HttpMethod.POST, "/leads/1/claim"),
        new Endpoint(HttpMethod.POST, "/leads/1/release")
    );

    @Autowired private MockMvc mockMvc;
    @Autowired private RequestMappingHandlerMapping handlerMapping;
    @Autowired private AccountMapper accountMapper;
    @Autowired private CustomerMapper customerMapper;
    @Autowired private LeadMapper leadMapper;
    @Autowired private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private Account admin;
    private Account salesA;
    private Account disabledSales;
    private Long poolLeadId;
    private Long salesALeadId;

    @BeforeEach
    void seed() {
        leadMapper.delete(null);
        customerMapper.delete(null);
        accountMapper.delete(null);

        admin = insertAccount("perm-admin@dealtrace.test", Role.ADMIN, AccountStatus.ENABLED);
        salesA = insertAccount("perm-sales-a@dealtrace.test", Role.SALES, AccountStatus.ENABLED);
        disabledSales = insertAccount("perm-disabled@dealtrace.test", Role.SALES, AccountStatus.DISABLED);

        Customer c = new Customer();
        c.setName("Perm Customer");
        c.setUsci(VALID_USCI);
        c.setCreatedAt(LocalDateTime.now());
        customerMapper.insert(c);

        poolLeadId = insertLead(c.getId(), null, LeadStage.UNTOUCHED);
        salesALeadId = insertLead(c.getId(), salesA.getId(), LeadStage.UNTOUCHED);
    }

    // ===================== INV-1 默认拒绝（自动发现） =====================

    @Test
    void anon_allNonWhitelistedEndpointsReturn401() throws Exception {
        Set<Endpoint> discovered = discoverProjectEndpoints();
        // 防真空通过：当前矩阵共 26 个 (method,path) 端点；发现机制失效（空集/极少）须大声失败而非静默放过
        assertThat(discovered).hasSizeGreaterThanOrEqualTo(20);

        for (Endpoint ep : discovered) {
            if (PERMIT_ALL.contains(ep.method() + " " + ep.path())) {
                continue;
            }
            String concrete = ep.path().replaceAll("\\{[^/}]+}", "1");
            mockMvc.perform(request(ep.method(), concrete))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
    }

    @Test
    void whitelist_healthReachableAnonymously() throws Exception {
        mockMvc.perform(request(HttpMethod.GET, "/health")).andExpect(status().isOk());
    }

    @Test
    void whitelist_loginReachesControllerAnonymously() throws Exception {
        // 匿名有效凭证 → 200 + 令牌，证明 permitAll 生效（未被安全层提前 401）
        mockMvc.perform(MockMvcRequestBuilders.post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + admin.getEmail() + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.data.token").isNotEmpty());
    }

    // ===================== INV-2 角色专属矩阵 =====================

    @Test
    void sales_adminOnlyEndpointsReturn403() throws Exception {
        String token = jwtService.generateToken(salesA);
        for (Endpoint ep : ADMIN_ONLY) {
            mockMvc.perform(request(ep.method(), ep.path()).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Test
    void admin_salesOnlyEndpointsReturn403() throws Exception {
        String token = jwtService.generateToken(admin);
        for (Endpoint ep : SALES_ONLY) {
            mockMvc.perform(request(ep.method(), ep.path()).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    // ===================== INV-3 停用 Sales 聚合失能 =====================

    @Test
    void disabledSales_cannotLogin() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + disabledSales.getEmail() + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void disabledSales_cannotClaim_tokenRejectedOwnershipUnchanged() throws Exception {
        // 停用账号令牌每请求被 filter 实时拒（401），认领无从发生
        String token = jwtService.generateToken(disabledSales);
        mockMvc.perform(MockMvcRequestBuilders.post("/leads/" + poolLeadId + "/claim")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        assertThat(leadMapper.selectById(poolLeadId).getOwnerSalesId()).isNull();
    }

    @Test
    void disabledSales_cannotBeAssignedOrTransferred_ownershipNeverLands() throws Exception {
        String adminToken = jwtService.generateToken(admin);

        // 分配公海单给停用 Sales → 拒绝，归属仍为空
        mockMvc.perform(MockMvcRequestBuilders.post("/leads/" + poolLeadId + "/assign")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"salesId\":" + disabledSales.getId() + "}"))
            .andExpect(status().isBadRequest());
        assertThat(leadMapper.selectById(poolLeadId).getOwnerSalesId()).isNull();

        // 转移 salesA 名下线索给停用 Sales → 拒绝，归属仍为 salesA
        mockMvc.perform(MockMvcRequestBuilders.post("/leads/" + salesALeadId + "/transfer")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"salesId\":" + disabledSales.getId() + "}"))
            .andExpect(status().isBadRequest());
        assertThat(leadMapper.selectById(salesALeadId).getOwnerSalesId()).isEqualTo(salesA.getId());
    }

    // ===================== INV-4 后端强校验（绕前端直达） =====================

    @Test
    void backendEnforcesWithoutFrontend_directRequestsRejected() throws Exception {
        // 匿名直达写端点 → 401（不依赖前端入口控制）
        mockMvc.perform(MockMvcRequestBuilders.post("/customers")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized());
        // 错误角色直达专属端点 → 403（后端按矩阵拒绝）
        String salesToken = jwtService.generateToken(salesA);
        mockMvc.perform(MockMvcRequestBuilders.get("/admin/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + salesToken))
            .andExpect(status().isForbidden());
    }

    // ===================== helpers =====================

    /** 枚举本项目（包名 com.dealtrace）已注册端点的 (method, 路径模板) 去重集合。 */
    private Set<Endpoint> discoverProjectEndpoints() {
        Set<Endpoint> result = new LinkedHashSet<>();
        for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod hm = entry.getValue();
            if (!hm.getBeanType().getPackageName().startsWith("com.dealtrace")) {
                continue; // 排除框架端点（如 BasicErrorController /error）
            }
            RequestMappingInfo info = entry.getKey();
            var ppc = info.getPathPatternsCondition();
            if (ppc == null) {
                continue;
            }
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (PathPattern pp : ppc.getPatterns()) {
                String path = pp.getPatternString();
                if (methods.isEmpty()) {
                    result.add(new Endpoint(HttpMethod.GET, path));
                } else {
                    for (RequestMethod rm : methods) {
                        result.add(new Endpoint(HttpMethod.valueOf(rm.name()), path));
                    }
                }
            }
        }
        return result;
    }

    private MockHttpServletRequestBuilder request(HttpMethod method, String path) {
        return MockMvcRequestBuilders.request(method, path);
    }

    private Account insertAccount(String email, Role role, AccountStatus status) {
        Account a = new Account();
        a.setEmail(email);
        a.setName(email);
        a.setRole(role);
        a.setStatus(status);
        a.setPasswordHash(passwordEncoder.encode(PASSWORD));
        LocalDateTime now = LocalDateTime.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        accountMapper.insert(a);
        return a;
    }

    private Long insertLead(Long customerId, Long ownerId, LeadStage stage) {
        Lead l = new Lead();
        l.setCustomerId(customerId);
        l.setBusinessYear((short) LocalDateTime.now().getYear());
        l.setBusinessType(BusinessType.BIM_CONSULTING);
        l.setContactName("X");
        l.setContactPhone("13800000000");
        l.setOwnerSalesId(ownerId);
        l.setStage(stage);
        l.setCreatedAt(LocalDateTime.now());
        leadMapper.insert(l);
        return l.getId();
    }

    private record Endpoint(HttpMethod method, String path) {
    }
}
