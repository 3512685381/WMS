package com.yinhui.springboot.system.service;

import com.yinhui.springboot.system.mapper.SysTokenBlacklistMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class TokenBlacklistService {

    private static final String BUSINESS_ZONE_NAME = "Asia/Shanghai";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of(BUSINESS_ZONE_NAME);

    private final SysTokenBlacklistMapper mapper;

    public TokenBlacklistService(SysTokenBlacklistMapper mapper) {
        this.mapper = mapper;
    }

    public boolean isRevoked(String jti) {
        return mapper.selectById(jti) != null;
    }

    public void revoke(String jti, Instant expiresAt) {
        mapper.revoke(jti, LocalDateTime.ofInstant(expiresAt, BUSINESS_ZONE));
    }

    @Scheduled(cron = "${wms.jwt.blacklist-cleanup-cron:0 0 * * * *}", zone = BUSINESS_ZONE_NAME)
    public void deleteExpired() {
        mapper.deleteExpired(LocalDateTime.now(BUSINESS_ZONE));
    }
}
