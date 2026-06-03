package com.dealtrace.contract.service;

import com.dealtrace.account.entity.Role;
import com.dealtrace.contract.dto.ContractPageView;
import com.dealtrace.contract.dto.ContractQuery;
import com.dealtrace.contract.dto.ContractRow;
import com.dealtrace.contract.dto.ContractRowView;
import com.dealtrace.contract.repository.ContractMapper;
import com.dealtrace.security.AccountPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 合同记录浏览读出编排（contract-view spec R1–R4）。
 *
 * <p>角色化可见范围（spec R1/R2）：ADMIN 用传入 {@code dealSalesId} 透传过滤；SALES 强制以
 * {@code principal.id()} 覆盖 {@code dealSalesId}（忽略越权入参），自然排除他人成交与公海赢单
 * （{@code deal_sales_id} 为 NULL）。签订日期闭区间与关键词在可见范围内再过滤。
 *
 * <p>展示组装（spec R3）：成交销售按 account <b>当前</b>姓名解析（JOIN 提供），公海赢单展示"公海赢单"；
 * 金额保留精确字符串（DECIMAL 标度，禁浮点），千分位交前端渲染。纯只读（spec R4），不改任何数据。
 */
@Service
public class ContractReadService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final String POOL_DEAL_LABEL = "公海赢单";

    private final ContractMapper contractMapper;

    public ContractReadService(ContractMapper contractMapper) {
        this.contractMapper = contractMapper;
    }

    @Transactional(readOnly = true)
    public ContractPageView list(ContractQuery query, AccountPrincipal principal) {
        Long effectiveDealSalesId =
            principal.role() == Role.SALES ? principal.id() : query.dealSalesId();

        int safeSize = Math.min(Math.max(query.size(), 1), MAX_PAGE_SIZE);
        int safePage = Math.max(query.page(), 1);
        long offset = (long) (safePage - 1) * safeSize;

        String keywordLike = (query.keyword() != null && !query.keyword().isBlank())
            ? "%" + query.keyword().strip() + "%"
            : null;

        long total = contractMapper.countPage(
            effectiveDealSalesId, query.signedDateFrom(), query.signedDateTo(), keywordLike);

        List<ContractRow> rows = contractMapper.selectPage(
            effectiveDealSalesId, query.signedDateFrom(), query.signedDateTo(), keywordLike, safeSize, offset);

        List<ContractRowView> items = new ArrayList<>(rows.size());
        for (ContractRow r : rows) {
            String dealSalesName = r.getDealSalesId() == null ? POOL_DEAL_LABEL : r.getDealSalesName();
            items.add(new ContractRowView(
                r.getLeadId(),
                r.getCustomerName(),
                r.getBusinessType(),
                r.getContractAmount(),
                r.getSignedDate(),
                r.getCreatedAt(),
                r.getDealSalesId(),
                dealSalesName));
        }
        return new ContractPageView(items, total, safePage, safeSize);
    }
}
