package com.yinhui.springboot.wms.service;

import com.yinhui.springboot.common.exception.ApiException;
import com.yinhui.springboot.wms.entity.WmsOrderSequence;
import com.yinhui.springboot.wms.mapper.WmsOrderSequenceMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单据编号：前缀 + 业务日期 + 四位序号，例如 RK202611280001。
 * Java 与 FastAPI 共用 wms_order_sequence，序号在单据事务内占用，事务回滚时一并释放。
 */
@Service
public class OrderNumberService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int MAX_DAILY_SEQUENCE = 9999;

    private final WmsOrderSequenceMapper sequenceMapper;

    public OrderNumberService(WmsOrderSequenceMapper sequenceMapper) {
        this.sequenceMapper = sequenceMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String next(String prefix, long userId) {
        LocalDate bizDate = today();
        try {
            sequenceMapper.allocate(prefix, bizDate, userId);
        } catch (DataIntegrityViolationException error) {
            throw new ApiException(HttpStatus.CONFLICT, "该类型今日单号已用完，请次日再试");
        }
        WmsOrderSequence sequence = sequenceMapper.selectForUpdate(prefix, bizDate);
        if (sequence == null || sequence.getLastNo() == null || sequence.getLastNo() > MAX_DAILY_SEQUENCE) {
            throw new ApiException(HttpStatus.CONFLICT, "该类型今日单号已用完，请次日再试");
        }
        return prefix + bizDate.format(DAY_FORMAT) + String.format("%04d", sequence.getLastNo());
    }

    public static LocalDate today() {
        return LocalDate.now(BUSINESS_ZONE);
    }
}
