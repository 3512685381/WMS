package com.yinhui.springboot.wms.mapper;

import com.yinhui.springboot.wms.entity.WmsOrderSequence;
import java.time.LocalDate;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface WmsOrderSequenceMapper {

    @Select("""
            SELECT order_prefix, biz_date, last_no, created_at, updated_at, created_by, is_deleted
            FROM wms_order_sequence
            WHERE order_prefix = #{prefix} AND biz_date = #{bizDate} AND is_deleted = 0
            """)
    WmsOrderSequence selectByKey(@Param("prefix") String prefix, @Param("bizDate") LocalDate bizDate);

    /**
     * 原子占用一个序号。插入或更新语句本身持有行锁，直到所在事务提交，Java 与 FastAPI 共用同一序列。
     */
    @Insert("""
            INSERT INTO wms_order_sequence (order_prefix, biz_date, last_no, created_by, is_deleted)
            VALUES (#{prefix}, #{bizDate}, 1, #{userId}, 0)
            ON DUPLICATE KEY UPDATE last_no = wms_order_sequence.last_no + 1,
                                    is_deleted = 0, updated_at = CURRENT_TIMESTAMP(3)
            """)
    int allocate(@Param("prefix") String prefix, @Param("bizDate") LocalDate bizDate,
            @Param("userId") long userId);

    @Select("""
            SELECT order_prefix, biz_date, last_no, created_at, updated_at, created_by, is_deleted
            FROM wms_order_sequence
            WHERE order_prefix = #{prefix} AND biz_date = #{bizDate}
            FOR UPDATE
            """)
    WmsOrderSequence selectForUpdate(@Param("prefix") String prefix, @Param("bizDate") LocalDate bizDate);
}
