package com.yinhui.springboot.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.yinhui.springboot.common.entity.AuditedEntity;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("wms_inventory")
public class WmsInventory extends AuditedEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long warehouseId;

    private Long locationId;

    private Long productId;

    private String batchNo;

    private LocalDate productionDate;

    private LocalDate expireDate;

    private BigDecimal quantity;

    private BigDecimal frozenQuantity;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private BigDecimal availableQuantity;

    @Version
    private Long version;
}
