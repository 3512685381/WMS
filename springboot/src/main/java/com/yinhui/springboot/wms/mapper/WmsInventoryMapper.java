package com.yinhui.springboot.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yinhui.springboot.wms.entity.WmsInventory;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface WmsInventoryMapper extends BaseMapper<WmsInventory> {

    /**
     * 按“仓库 + 库位 + 商品 + 批次”锁定库存行。记账服务先在固定顺序下锁行，再统一更新，避免并发死锁。
     */
    @Select("""
            SELECT * FROM wms_inventory
            WHERE warehouse_id = #{warehouseId} AND location_id = #{locationId}
              AND product_id = #{productId} AND batch_no = #{batchNo} AND is_deleted = 0
            FOR UPDATE
            """)
    WmsInventory selectForUpdate(@Param("warehouseId") long warehouseId,
            @Param("locationId") long locationId, @Param("productId") long productId,
            @Param("batchNo") String batchNo);

    @Select("""
            SELECT * FROM wms_inventory
            WHERE warehouse_id = #{warehouseId} AND location_id = #{locationId}
              AND product_id = #{productId} AND batch_no = #{batchNo} AND is_deleted = 0
            """)
    WmsInventory selectByDimension(@Param("warehouseId") long warehouseId,
            @Param("locationId") long locationId, @Param("productId") long productId,
            @Param("batchNo") String batchNo);

    @Update("""
            UPDATE wms_inventory
            SET quantity = #{quantity}, frozen_quantity = #{frozenQuantity},
                version = version + 1, updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id}
            """)
    int updateQuantities(@Param("id") long id, @Param("quantity") BigDecimal quantity,
            @Param("frozenQuantity") BigDecimal frozenQuantity);

    /**
     * 首次入库时补齐生产日期与效期，已有值保持不变。
     */
    @Update("""
            UPDATE wms_inventory
            SET production_date = COALESCE(production_date, #{productionDate}),
                expire_date = COALESCE(expire_date, #{expireDate}),
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id}
            """)
    int fillDates(@Param("id") long id, @Param("productionDate") LocalDate productionDate,
            @Param("expireDate") LocalDate expireDate);
}
