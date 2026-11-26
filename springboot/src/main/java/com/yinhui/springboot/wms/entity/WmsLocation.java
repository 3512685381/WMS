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
@TableName("wms_location")
public class WmsLocation extends AuditedEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long warehouseId;

    private Long areaId;

    private String code;

    private BigDecimal maxCapacity;

    private Integer status;
}
