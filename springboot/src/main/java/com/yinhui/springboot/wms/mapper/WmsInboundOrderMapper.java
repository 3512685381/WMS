package com.yinhui.springboot.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yinhui.springboot.wms.entity.WmsInboundOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface WmsInboundOrderMapper extends BaseMapper<WmsInboundOrder> {

    /**
     * 锁定单据行，用于提交、审核、驳回等状态迁移；必须在事务内调用。
     */
    @Select("SELECT * FROM wms_inbound_order WHERE id = #{id} AND is_deleted = 0 FOR UPDATE")
    WmsInboundOrder selectForUpdate(@Param("id") long id);
}
