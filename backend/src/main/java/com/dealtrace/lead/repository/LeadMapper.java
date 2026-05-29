package com.dealtrace.lead.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dealtrace.lead.entity.Lead;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LeadMapper extends BaseMapper<Lead> {
}
