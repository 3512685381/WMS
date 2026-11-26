package com.yinhui.springboot.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yinhui.springboot.system.entity.SysMenu;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SysMenuMapper extends BaseMapper<SysMenu> {

    @Select("""
            SELECT m.id, m.parent_id, m.menu_name, m.path, m.component, m.icon,
                   m.menu_type, m.perms, m.sort_order
            FROM sys_menu m
            WHERE m.is_deleted = 0
              AND m.menu_type IN ('M', 'C')
              AND EXISTS (
                  SELECT 1
                  FROM sys_user_role ur
                  JOIN sys_role r ON r.id = ur.role_id AND r.status = 1 AND r.is_deleted = 0
                  JOIN sys_role_menu rm ON rm.role_id = r.id AND rm.is_deleted = 0
                  WHERE ur.user_id = #{userId} AND ur.is_deleted = 0 AND rm.menu_id = m.id
              )
            ORDER BY m.sort_order, m.id
            """)
    List<SysMenu> selectRoutesForUser(@Param("userId") long userId);
}
