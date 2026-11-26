package com.yinhui.springboot.wms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yinhui.springboot.common.entity.AuditedEntity;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("wms_outbound_order")
public class WmsOutboundOrder extends AuditedEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    private Long warehouseId;

    private Long customerId;

    private String source;

    private String requestKey;

    private Integer status;

    private String remark;

    private LocalDateTime submittedAt;

    private Long reviewedBy;

    private LocalDateTime reviewedAt;

    private String rejectReason;

    private LocalDateTime postedAt;
}
