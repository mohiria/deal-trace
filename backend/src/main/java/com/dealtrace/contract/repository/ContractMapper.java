package com.dealtrace.contract.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dealtrace.contract.entity.Contract;
import org.apache.ibatis.annotations.Mapper;

/**
 * 合同记录数据访问（lead-closure）。仅 BaseMapper：插入由赢单事务调用，
 * 每线索≤1 由 contract 表 lead_id UNIQUE 兜底；本片不提供合同读取端点（design D9）。
 */
@Mapper
public interface ContractMapper extends BaseMapper<Contract> {
}
