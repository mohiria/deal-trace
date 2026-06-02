package com.dealtrace.systemlog.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dealtrace.systemlog.entity.SystemLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 系统日志读取数据访问（view-system-log）。
 *
 * <p>仅读：写入走 {@code JdbcSystemLogPort}（直 JdbcTemplate INSERT），无 UPDATE/DELETE（日志不可变）。
 * 线索维度按 {@code created_at} 倒序、{@code id} 倒序兜底（同毫秒稳定排序）；全局分页用 {@link BaseMapper}
 * 的 {@code selectList/selectCount} + {@code QueryWrapper}（动态 action/target_type 过滤，见 service）。
 */
@Mapper
public interface SystemLogMapper extends BaseMapper<SystemLog> {

    /** 按线索读取全部系统日志，created_at 倒序、id 倒序兜底。 */
    @Select("SELECT * FROM system_log WHERE lead_id = #{leadId} ORDER BY created_at DESC, id DESC")
    List<SystemLog> selectByLeadIdOrderByCreatedAtDesc(@Param("leadId") Long leadId);
}
