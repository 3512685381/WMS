package com.yinhui.springboot.wms.controller;

import com.yinhui.springboot.common.result.Result;
import com.yinhui.springboot.wms.dto.MasterViews;
import com.yinhui.springboot.wms.dto.PageResult;
import com.yinhui.springboot.wms.service.InventoryQueryService;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wms/inventory")
public class InventoryController {

    private final InventoryQueryService inventoryQueryService;

    public InventoryController(InventoryQueryService inventoryQueryService) {
        this.inventoryQueryService = inventoryQueryService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('wms:inventory:list')")
    public Result<PageResult<MasterViews.InventoryRow>> list(@RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size, @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long locationId, @RequestParam(required = false) Long productId,
            @RequestParam(required = false) String batchNo, @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "false") boolean onlyAvailable) {
        return Result.success(inventoryQueryService.inventory(warehouseId, locationId, productId, batchNo, keyword,
                onlyAvailable, PageResult.safePage(page), PageResult.safeSize(size)));
    }

    @GetMapping("/logs")
    @PreAuthorize("hasAuthority('wms:inventory:logs')")
    public Result<PageResult<MasterViews.InventoryLogRow>> logs(@RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size, @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long productId, @RequestParam(required = false) String bizType,
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        return Result.success(inventoryQueryService.logs(warehouseId, productId, bizType, orderNo, dateFrom,
                dateTo, PageResult.safePage(page), PageResult.safeSize(size)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:inventory:list')")
    public Result<MasterViews.InventoryRow> detail(@PathVariable long id) {
        return Result.success(inventoryQueryService.inventoryItem(id));
    }
}
