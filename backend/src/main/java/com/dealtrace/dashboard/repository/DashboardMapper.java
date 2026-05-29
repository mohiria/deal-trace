package com.dealtrace.dashboard.repository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Dashboard 指标只读聚合查询（spec dashboard / design D5）。
 *
 * <p>全部为 COUNT/SUM，不写任何状态、不写系统日志。复用既有索引：
 * {@code idx_lead_owner_created}（今日新增 owner+created_at）、
 * {@code idx_lead_stage_created}（公海 stage 过滤）。
 *
 * <p>口径约定：参数 {@code me} 为 null 表示全局口径（Admin），非 null 表示个人口径（Sales，
 * 仅统计归属/成交销售为该 id 者）。无主线索（owner 或 deal_sales_id 为 NULL）在个人口径下
 * 因 {@code col = #{me}} 不成立而自然排除（design D1）。
 *
 * <p>时间窗由 service 以服务端 LocalDateTime 计算后传入，闭起开止：{@code [from, to)}（design D4）。
 *
 * <p>注意：表名 {@code lead} 是 MySQL 8 保留字，原生 SQL 必须反引号；阶段以中文 dbValue 存储
 * （已赢单 / 已流失），故未结束判定用 {@code stage NOT IN ('已赢单','已流失')}。
 */
@Mapper
public interface DashboardMapper {

    /**
     * ① 今日新增业务线索数（存量类，按当前归属）。
     * me=null → 全局；me!=null → 仅当前归属为 me。created_at ∈ [from, to)。
     */
    @Select("""
        SELECT COUNT(*) FROM `lead`
        WHERE created_at >= #{from} AND created_at < #{to}
          AND (#{me,jdbcType=BIGINT} IS NULL OR owner_sales_id = #{me,jdbcType=BIGINT})
        """)
    long countTodayNew(@Param("from") LocalDateTime from,
                       @Param("to") LocalDateTime to,
                       @Param("me") Long me);

    /**
     * ② 当前公海待认领数（存量类，两视角同值 / 恒全局）。
     * owner_sales_id IS NULL 且未结束（阶段不为已赢单 / 已流失）。不按 me 过滤。
     */
    @Select("""
        SELECT COUNT(*) FROM `lead`
        WHERE owner_sales_id IS NULL
          AND stage NOT IN ('已赢单', '已流失')
        """)
    long countOpenSeaUnclaimed();

    /**
     * ③ 本月赢单总金额（事件类，按赢单时刻归属 contract.deal_sales_id）。
     * 以 lead.won_at ∈ [from, to) 为锚（非 signed_date）。me=null → 全局；me!=null → deal_sales_id=me。
     * 空集返回 NULL，由 service 归一为 0（design D3）。
     */
    @Select("""
        SELECT SUM(c.contract_amount)
        FROM contract c
        JOIN `lead` l ON l.id = c.lead_id
        WHERE l.won_at >= #{from} AND l.won_at < #{to}
          AND (#{me,jdbcType=BIGINT} IS NULL OR c.deal_sales_id = #{me,jdbcType=BIGINT})
        """)
    BigDecimal sumMonthlyWonAmount(@Param("from") LocalDateTime from,
                                   @Param("to") LocalDateTime to,
                                   @Param("me") Long me);

    /**
     * ④ 本月赢单事件数（流失率分母的一半）。事件时归属取 contract.deal_sales_id。
     * lead.won_at ∈ [from, to)。me=null → 全局；me!=null → deal_sales_id=me。
     */
    @Select("""
        SELECT COUNT(*)
        FROM contract c
        JOIN `lead` l ON l.id = c.lead_id
        WHERE l.won_at >= #{from} AND l.won_at < #{to}
          AND (#{me,jdbcType=BIGINT} IS NULL OR c.deal_sales_id = #{me,jdbcType=BIGINT})
        """)
    long countMonthlyWonEvents(@Param("from") LocalDateTime from,
                               @Param("to") LocalDateTime to,
                               @Param("me") Long me);

    /**
     * ④ 本月流失事件数（流失率分子，也是分母的另一半）。事件时归属取 lead.owner_sales_id
     * （流失为终态，归属已冻结，design D1）。lead.lost_at ∈ [from, to)。
     * me=null → 全局；me!=null → owner_sales_id=me。
     */
    @Select("""
        SELECT COUNT(*) FROM `lead`
        WHERE lost_at >= #{from} AND lost_at < #{to}
          AND (#{me,jdbcType=BIGINT} IS NULL OR owner_sales_id = #{me,jdbcType=BIGINT})
        """)
    long countMonthlyLostEvents(@Param("from") LocalDateTime from,
                                @Param("to") LocalDateTime to,
                                @Param("me") Long me);
}
