package com.yinhui.springboot.wms.controller;

import com.yinhui.springboot.common.result.Result;
import com.yinhui.springboot.wms.dto.MasterViews;
import com.yinhui.springboot.wms.dto.PageResult;
import com.yinhui.springboot.wms.service.InventoryQueryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wms/alerts")
public class AlertController {

    private final InventoryQueryService inventoryQueryService;

    public AlertController(InventoryQueryService inventoryQueryService) {
        this.inventoryQueryService = inventoryQueryService;
    }

    @GetMapping("/low-stock")
    @PreAuthorize("hasAuthority('wms:alert:list')")
    public Result<PageResult<MasterViews.LowStockRow>> lowStock(@RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size, @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "true") boolean includeMissing) {
        return Result.success(inventoryQueryService.lowStock(warehouseId, keyword, includeMissing,
                PageResult.safePage(page), PageResult.safeSize(size)));
    }

    @GetMapping("/overstock")
    @PreAuthorize("hasAuthority('wms:alert:list')")
    public Result<PageResult<MasterViews.OverstockRow>> overstock(@RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size, @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) String keyword) {
        return Result.success(inventoryQueryService.overstock(warehouseId, keyword,
                PageResult.safePage(page), PageResult.safeSize(size)));
    }

    @GetMapping("/expiring")
    @PreAuthorize("hasAuthority('wms:alert:list')")
    public Result<PageResult<MasterViews.ExpiringRow>> expiring(@RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size, @RequestParam(required = false) Long warehouseId,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "EXPIRING") String scope) {
        return Result.success(inventoryQueryService.expiring(warehouseId, days, scope,
                PageResult.safePage(page), PageResult.safeSize(size)));
    }
}
