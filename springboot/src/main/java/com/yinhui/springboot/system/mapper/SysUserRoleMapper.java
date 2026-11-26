package com.yinhui.springboot.system.mapper;

import com.yinhui.springboot.system.entity.SysUserRole;
import java.util.List;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SysUserRoleMapper {

    @Select("""
            SELECT user_id, role_id, created_at, updated_at, created_by, is_deleted
            FROM sys_user_role
            WHERE user_id = #{userId} AND role_id = #{roleId} AND is_deleted = 0
            """)
    SysUserRole selectByKey(@Param("userId") long userId, @Param("roleId") long roleId);

    @Select("""
            SELECT DISTINCT r.role_code, m.perms AS permission_code
            FROM sys_user_role ur
            JOIN sys_role r ON r.id = ur.role_id AND r.is_deleted = 0 AND r.status = 1
            LEFT JOIN sys_role_menu rm ON rm.role_id = r.id AND rm.is_deleted = 0
            LEFT JOIN sys_menu m ON m.id = rm.menu_id AND m.is_deleted = 0
                                AND m.perms IS NOT NULL AND m.perms <> ''
            WHERE ur.user_id = #{userId} AND ur.is_deleted = 0
            ORDER BY r.role_code, permission_code
            """)
    @ConstructorArgs({
            @Arg(column = "role_code", javaType = String.class),
            @Arg(column = "permission_code", javaType = String.class)
    })
    List<RoleGrant> selectActiveGrants(@Param("userId") long userId);

    record RoleGrant(String roleCode, String permission) {
    }
}
