package com.yinhui.springboot.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yinhui.springboot.wms.entity.WmsFreezeOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface WmsFreezeOrderMapper extends BaseMapper<WmsFreezeOrder> {

    @Select("SELECT * FROM wms_freeze_order WHERE id = #{id} AND is_deleted = 0 FOR UPDATE")
    WmsFreezeOrder selectForUpdate(@Param("id") long id);
}
