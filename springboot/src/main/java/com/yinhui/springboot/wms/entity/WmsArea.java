package com.yinhui.springboot.wms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yinhui.springboot.common.entity.AuditedEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("wms_area")
public class WmsArea extends AuditedEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long warehouseId;

    private String code;

    private String name;

    private String areaType;
}
