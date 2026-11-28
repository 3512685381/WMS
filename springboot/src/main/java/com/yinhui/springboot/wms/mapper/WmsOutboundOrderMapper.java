package com.yinhui.springboot.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yinhui.springboot.wms.entity.WmsOutboundOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface WmsOutboundOrderMapper extends BaseMapper<WmsOutboundOrder> {

    @Select("SELECT * FROM wms_outbound_order WHERE id = #{id} AND is_deleted = 0 FOR UPDATE")
    WmsOutboundOrder selectForUpdate(@Param("id") long id);
}
