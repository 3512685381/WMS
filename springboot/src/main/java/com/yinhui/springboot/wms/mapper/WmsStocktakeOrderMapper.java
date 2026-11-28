package com.yinhui.springboot.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yinhui.springboot.wms.entity.WmsStocktakeOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface WmsStocktakeOrderMapper extends BaseMapper<WmsStocktakeOrder> {

    @Select("SELECT * FROM wms_stocktake_order WHERE id = #{id} AND is_deleted = 0 FOR UPDATE")
    WmsStocktakeOrder selectForUpdate(@Param("id") long id);
}
