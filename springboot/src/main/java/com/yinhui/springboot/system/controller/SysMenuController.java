package com.yinhui.springboot.system.controller;

import com.yinhui.springboot.common.result.Result;
import com.yinhui.springboot.common.security.CurrentUser;
import com.yinhui.springboot.system.dto.MenuTreeNode;
import com.yinhui.springboot.system.service.SysMenuService;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sys/menus")
public class SysMenuController {

    private final SysMenuService menuService;

    public SysMenuController(SysMenuService menuService) {
        this.menuService = menuService;
    }

    @GetMapping("/my")
    public Result<List<MenuTreeNode>> myMenus(@AuthenticationPrincipal CurrentUser principal) {
        return Result.success(menuService.getMyMenuTree(principal.user().id()));
    }
}
