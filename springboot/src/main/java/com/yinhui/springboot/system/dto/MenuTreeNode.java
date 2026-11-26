package com.yinhui.springboot.system.dto;

import java.util.List;

public record MenuTreeNode(
        Long id,
        Long parentId,
        String menuName,
        String path,
        String component,
        String icon,
        String menuType,
        String perms,
        Integer sortOrder,
        List<MenuTreeNode> children) {
}
