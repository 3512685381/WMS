package com.yinhui.springboot.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yinhui.springboot.common.entity.AuditedEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("sys_role_menu")
public class SysRoleMenu extends AuditedEntity {

    private Long roleId;

    private Long menuId;
}
