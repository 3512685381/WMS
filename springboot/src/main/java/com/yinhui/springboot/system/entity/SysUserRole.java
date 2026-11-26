package com.yinhui.springboot.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yinhui.springboot.common.entity.AuditedEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("sys_user_role")
public class SysUserRole extends AuditedEntity {

    private Long userId;

    private Long roleId;
}
