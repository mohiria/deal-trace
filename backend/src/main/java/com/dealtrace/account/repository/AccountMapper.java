package com.dealtrace.account.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dealtrace.account.entity.Account;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AccountMapper extends BaseMapper<Account> {
}
