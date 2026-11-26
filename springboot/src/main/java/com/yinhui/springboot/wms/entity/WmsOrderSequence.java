package com.yinhui.springboot.wms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yinhui.springboot.common.entity.AuditedEntity;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("wms_order_sequence")
public class WmsOrderSequence extends AuditedEntity {

    private String orderPrefix;

    private LocalDate bizDate;

    private Integer lastNo;
}
