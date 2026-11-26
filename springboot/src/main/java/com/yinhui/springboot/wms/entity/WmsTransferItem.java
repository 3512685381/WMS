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
@TableName("wms_transfer_item")
public class WmsTransferItem extends AuditedEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;

    private Long fromWarehouseId;

    private Long toWarehouseId;

    private Long productId;

    private Long fromLocationId;

    private Long toLocationId;

    private String batchNo;

    private BigDecimal quantity;
}
