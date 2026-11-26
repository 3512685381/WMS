package com.yinhui.springboot.wms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yinhui.springboot.common.entity.AuditedEntity;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("wms_inventory_log")
public class WmsInventoryLog extends AuditedEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long inventoryId;

    private Long warehouseId;

    private Long locationId;

    private Long productId;

    private String batchNo;

    private String bizType;

    private String orderNo;

    private BigDecimal beforeQty;

    private BigDecimal changeQty;

    private BigDecimal afterQty;

    private BigDecimal beforeFrozenQty;

    private BigDecimal changeFrozenQty;

    private BigDecimal afterFrozenQty;

    private Long operatorId;
}
