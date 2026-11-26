package com.yinhui.springboot.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yinhui.springboot.system.entity.SysTokenBlacklist;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface SysTokenBlacklistMapper extends BaseMapper<SysTokenBlacklist> {

    @Insert("""
            INSERT INTO sys_token_blacklist (jti, expire_at)
            VALUES (#{jti}, #{expiresAt})
            ON DUPLICATE KEY UPDATE jti = sys_token_blacklist.jti
            """)
    void revoke(@Param("jti") String jti, @Param("expiresAt") LocalDateTime expiresAt);

    @Delete("DELETE FROM sys_token_blacklist WHERE expire_at <= #{now}")
    int deleteExpired(@Param("now") LocalDateTime now);
}
