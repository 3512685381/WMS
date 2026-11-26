package com.yinhui.springboot.wms.mapper;

import com.yinhui.springboot.wms.entity.WmsOrderSequence;
import java.time.LocalDate;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface WmsOrderSequenceMapper {

    @Select("""
            SELECT order_prefix, biz_date, last_no, created_at, updated_at, created_by, is_deleted
            FROM wms_order_sequence
            WHERE order_prefix = #{prefix} AND biz_date = #{bizDate} AND is_deleted = 0
            """)
    WmsOrderSequence selectByKey(@Param("prefix") String prefix, @Param("bizDate") LocalDate bizDate);
}
