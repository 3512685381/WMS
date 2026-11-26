package com.yinhui.springboot.system.mapper;

import com.yinhui.springboot.system.entity.SysRoleMenu;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SysRoleMenuMapper {

    @Select("""
            SELECT role_id, menu_id, created_at, updated_at, created_by, is_deleted
            FROM sys_role_menu
            WHERE role_id = #{roleId} AND menu_id = #{menuId} AND is_deleted = 0
            """)
    SysRoleMenu selectByKey(@Param("roleId") long roleId, @Param("menuId") long menuId);
}
