package com.dealtrace.progresslog.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dealtrace.progresslog.entity.ProgressLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 进度跟踪数据访问（progress-log）。插入由新增进度事务调用；读取按 track_time 倒序（PRD §7.8.6）。
 * 无 UPDATE / DELETE 方法——进度一经写入不可变（design D10）。
 */
@Mapper
public interface ProgressLogMapper extends BaseMapper<ProgressLog> {

    /** 按线索读取全部进度，track_time 倒序、id 倒序兜底（同毫秒稳定排序）。 */
    @Select("SELECT * FROM progress_log WHERE lead_id = #{leadId} ORDER BY track_time DESC, id DESC")
    List<ProgressLog> selectByLeadIdOrderByTrackTimeDesc(@Param("leadId") Long leadId);
}
