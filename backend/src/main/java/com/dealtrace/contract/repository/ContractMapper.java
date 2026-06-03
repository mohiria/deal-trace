package com.dealtrace.contract.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dealtrace.contract.dto.ContractRow;
import com.dealtrace.contract.entity.Contract;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * 合同记录数据访问。写侧（lead-closure）：插入由赢单事务调用，每线索≤1 由 lead_id UNIQUE 兜底。
 * 读侧（contract-view）：JOIN customer/lead 取客户名与业务类型、LEFT JOIN account 取成交销售当前姓名，
 * 按 created_at 倒序分页 + 可选筛选（成交销售 / 签订日期闭区间 / 客户名·业务类型关键词）。
 *
 * <p>{@code lead} 为 MySQL 8 保留字，JOIN 引用反引号。{@code contract_amount} 以列别名读为 String
 * 保留 DECIMAL 精确标度。可选条件用 {@code <script>} 动态拼接：参数为 null 时该子句不进入 SQL。
 */
@Mapper
public interface ContractMapper extends BaseMapper<Contract> {

    String JOINS = "FROM contract c "
        + "JOIN `lead` l ON l.id = c.lead_id "
        + "JOIN customer cu ON cu.id = l.customer_id "
        + "LEFT JOIN account a ON a.id = c.deal_sales_id ";

    String FILTERS = "<where>"
        + "<if test='dealSalesId != null'> AND c.deal_sales_id = #{dealSalesId}</if>"
        + "<if test='signedDateFrom != null'> AND c.signed_date &gt;= #{signedDateFrom}</if>"
        + "<if test='signedDateTo != null'> AND c.signed_date &lt;= #{signedDateTo}</if>"
        + "<if test='keywordLike != null'> AND (cu.name LIKE #{keywordLike} OR l.business_type LIKE #{keywordLike})</if>"
        + "</where>";

    /** 倒序分页查询投影行（created_at DESC, id DESC 稳定排序）。 */
    @Select("<script>"
        + "SELECT c.lead_id AS leadId, cu.name AS customerName, l.business_type AS businessType, "
        + "c.contract_amount AS contractAmount, c.signed_date AS signedDate, c.created_at AS createdAt, "
        + "c.deal_sales_id AS dealSalesId, a.name AS dealSalesName "
        + JOINS
        + FILTERS
        + " ORDER BY c.created_at DESC, c.id DESC "
        + "LIMIT #{limit} OFFSET #{offset}"
        + "</script>")
    List<ContractRow> selectPage(
        @Param("dealSalesId") Long dealSalesId,
        @Param("signedDateFrom") LocalDate signedDateFrom,
        @Param("signedDateTo") LocalDate signedDateTo,
        @Param("keywordLike") String keywordLike,
        @Param("limit") int limit,
        @Param("offset") long offset);

    /** 与 selectPage 同条件的总数（供分页）。 */
    @Select("<script>"
        + "SELECT COUNT(*) "
        + JOINS
        + FILTERS
        + "</script>")
    long countPage(
        @Param("dealSalesId") Long dealSalesId,
        @Param("signedDateFrom") LocalDate signedDateFrom,
        @Param("signedDateTo") LocalDate signedDateTo,
        @Param("keywordLike") String keywordLike);
}
