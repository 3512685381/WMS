package com.yinhui.springboot.system.service;

import com.yinhui.springboot.system.dto.MenuTreeNode;
import com.yinhui.springboot.system.entity.SysMenu;
import com.yinhui.springboot.system.mapper.SysMenuMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class SysMenuService {

    private final SysMenuMapper menuMapper;

    public SysMenuService(SysMenuMapper menuMapper) {
        this.menuMapper = menuMapper;
    }

    public List<MenuTreeNode> getMyMenuTree(long userId) {
        var menus = menuMapper.selectRoutesForUser(userId);
        var visibleIds = menus.stream().map(SysMenu::getId).collect(Collectors.toSet());
        Map<Long, List<SysMenu>> menusByParent = new HashMap<>();

        for (SysMenu menu : menus) {
            // 独立授权且没有可见父节点的路由放在根级。
            Long parentId = visibleIds.contains(menu.getParentId()) ? menu.getParentId() : null;
            menusByParent.computeIfAbsent(parentId, ignored -> new ArrayList<>()).add(menu);
        }
        return assemble(menusByParent, null);
    }

    private static List<MenuTreeNode> assemble(Map<Long, List<SysMenu>> menusByParent, Long parentId) {
        return menusByParent.getOrDefault(parentId, List.of()).stream()
                .map(menu -> new MenuTreeNode(
                        menu.getId(), parentId, menu.getMenuName(), menu.getPath(), menu.getComponent(),
                        menu.getIcon(), menu.getMenuType(), menu.getPerms(), menu.getSortOrder(),
                        assemble(menusByParent, menu.getId())))
                .toList();
    }
}
