package com.yinhui.springboot.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yinhui.springboot.wms.entity.WmsTransferOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface WmsTransferOrderMapper extends BaseMapper<WmsTransferOrder> {

    @Select("SELECT * FROM wms_transfer_order WHERE id = #{id} AND is_deleted = 0 FOR UPDATE")
    WmsTransferOrder selectForUpdate(@Param("id") long id);
}
