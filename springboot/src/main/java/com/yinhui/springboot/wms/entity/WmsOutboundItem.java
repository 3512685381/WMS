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
@TableName("wms_outbound_item")
public class WmsOutboundItem extends AuditedEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;

    private Long warehouseId;

    private Long productId;

    private Long locationId;

    private String batchNo;

    private BigDecimal quantity;

    private BigDecimal unitPrice;
}
