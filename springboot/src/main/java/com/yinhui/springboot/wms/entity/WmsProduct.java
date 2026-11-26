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
@TableName("wms_product")
public class WmsProduct extends AuditedEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String skuCode;

    private String name;

    private Long categoryId;

    private String spec;

    private String unit;

    private Integer batchManaged;

    private BigDecimal minStock;

    private BigDecimal maxStock;

    private Long shelfLifeDays;

    private Long defaultSupplierId;

    private BigDecimal referencePrice;
}
