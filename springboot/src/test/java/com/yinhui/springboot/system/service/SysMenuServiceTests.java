package com.yinhui.springboot.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yinhui.springboot.system.dto.MenuTreeNode;
import com.yinhui.springboot.system.entity.SysMenu;
import com.yinhui.springboot.system.mapper.SysMenuMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class SysMenuServiceTests {

    private final SysMenuMapper mapper = mock(SysMenuMapper.class);
    private final SysMenuService service = new SysMenuService(mapper);

    @Test
    void groupsAuthorizedMenusAndKeepsSiblingOrder() {
        when(mapper.selectRoutesForUser(42L)).thenReturn(List.of(
                menu(102, 100L, "/admin/master/location", 10),
                menu(103, 100L, "/admin/master/product", 10),
                menu(100, null, "/admin/master", 20)));

        var tree = service.getMyMenuTree(42L);

        assertThat(tree).extracting(MenuTreeNode::id).containsExactly(100L);
        assertThat(tree.getFirst().children()).extracting(MenuTreeNode::id).containsExactly(102L, 103L);
        assertThat(tree.getFirst().children()).allSatisfy(child -> {
            assertThat(child.parentId()).isEqualTo(100L);
            assertThat(child.children()).isEmpty();
        });
    }

    @Test
    void putsIndependentlyGrantedRoutesAtRootAndSkipsParentCycles() {
        when(mapper.selectRoutesForUser(42L)).thenReturn(List.of(
                menu(102, 100L, "/admin/master/location", 10),
                menu(200, 201L, "/cycle-a", 20),
                menu(201, 200L, "/cycle-b", 20)));

        var tree = service.getMyMenuTree(42L);

        assertThat(tree).extracting(MenuTreeNode::id).containsExactly(102L);
        assertThat(tree.getFirst().parentId()).isNull();
        assertThat(tree.getFirst().path()).isEqualTo("/admin/master/location");
    }

    private static SysMenu menu(long id, Long parentId, String path, int sortOrder) {
        var menu = new SysMenu();
        menu.setId(id);
        menu.setParentId(parentId);
        menu.setPath(path);
        menu.setSortOrder(sortOrder);
        return menu;
    }
}
