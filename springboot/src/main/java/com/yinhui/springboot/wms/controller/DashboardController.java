package com.yinhui.springboot.wms.controller;

import com.yinhui.springboot.common.result.Result;
import com.yinhui.springboot.wms.dto.MasterViews;
import com.yinhui.springboot.wms.service.InventoryQueryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wms/dashboard")
public class DashboardController {

    private final InventoryQueryService inventoryQueryService;

    public DashboardController(InventoryQueryService inventoryQueryService) {
        this.inventoryQueryService = inventoryQueryService;
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('wms:dashboard:view')")
    public Result<MasterViews.DashboardSummary> summary() {
        return Result.success(inventoryQueryService.dashboard());
    }
}
