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
@TableName("wms_freeze_order")
public class WmsFreezeOrder extends AuditedEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    private Long warehouseId;

    private Integer status;

    private String remark;

    private LocalDateTime submittedAt;

    private Long reviewedBy;

    private LocalDateTime reviewedAt;

    private String rejectReason;

    private LocalDateTime postedAt;
}
