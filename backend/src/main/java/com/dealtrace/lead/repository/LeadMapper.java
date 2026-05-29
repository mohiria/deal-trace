package com.dealtrace.lead.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dealtrace.lead.entity.Lead;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 线索数据访问。lead-core 仅用 BaseMapper；lead-ownership 追加：
 * <ul>
 *   <li>{@link #selectByIdForUpdate(Long)}：行锁读，5 个归属写动作的并发兜底（design D1）</li>
 *   <li>{@link #updateOwner(Long, Long)}：定向更新归属，ownerSalesId 可为 NULL（回收 / 退回入池）</li>
 * </ul>
 * 公海列表查询走 service 层 QueryWrapper（与 allLeads / myLeads 同模式），不在此另立方法。
 *
 * <p>注意：表名 {@code lead} 是 MySQL 8 保留字，原生 SQL 必须反引号。
 */
@Mapper
public interface LeadMapper extends BaseMapper<Lead> {

    /** 加行锁读取目标线索；调用方须处于事务中，否则锁立即释放。 */
    @Select("SELECT * FROM `lead` WHERE id = #{id} FOR UPDATE")
    Lead selectByIdForUpdate(@Param("id") Long id);

    /** 仅更新归属列；ownerSalesId 传 null 即移入公海。返回受影响行数。 */
    @Update("UPDATE `lead` SET owner_sales_id = #{ownerSalesId} WHERE id = #{id}")
    int updateOwner(@Param("id") Long id, @Param("ownerSalesId") Long ownerSalesId);

    /**
     * 仅更新阶段列（lead-stage）；{@code stage} 传 {@link com.dealtrace.lead.entity.LeadStage#getDbValue()}
     * 中文枚举值。定向更新避免触碰 lastTrackedAt 等 ALWAYS 字段（spec：阶段变更不触 lastTrackedAt）。
     */
    @Update("UPDATE `lead` SET stage = #{stage} WHERE id = #{id}")
    int updateStage(@Param("id") Long id, @Param("stage") String stage);

    /**
     * 标记赢单（lead-closure）：定向更新 stage + won_at。{@code stage} 传 LeadStage.WON 的 dbValue，
     * {@code wonAt} 由服务端时钟生成。合同记录由赢单事务另行插入。
     */
    @Update("UPDATE `lead` SET stage = #{stage}, won_at = #{wonAt} WHERE id = #{id}")
    int updateWon(@Param("id") Long id, @Param("stage") String stage,
                  @Param("wonAt") java.time.LocalDateTime wonAt);

    /**
     * 标记流失（lead-closure）：定向更新 stage + lost_at + lose_reason + lose_note。
     * {@code loseReason} 传 LoseReason 的 dbValue；{@code loseNote} 可为 null。
     */
    @Update("UPDATE `lead` SET stage = #{stage}, lost_at = #{lostAt}, "
        + "lose_reason = #{loseReason}, lose_note = #{loseNote} WHERE id = #{id}")
    int updateLost(@Param("id") Long id, @Param("stage") String stage,
                   @Param("lostAt") java.time.LocalDateTime lostAt,
                   @Param("loseReason") String loseReason, @Param("loseNote") String loseNote);

    /**
     * 同步最后跟踪时间（progress-log）：仅更新 last_tracked_at。{@code trackTime} 须与新增进度的
     * track_time 同值同源（同一次服务端 now，design D4）。定向更新避免触碰其它列。
     */
    @Update("UPDATE `lead` SET last_tracked_at = #{trackTime} WHERE id = #{id}")
    int updateLastTrackedAt(@Param("id") Long id,
                            @Param("trackTime") java.time.LocalDateTime trackTime);
}
