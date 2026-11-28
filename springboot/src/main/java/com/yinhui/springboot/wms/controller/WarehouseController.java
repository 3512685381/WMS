package com.yinhui.springboot.wms.controller;

import com.yinhui.springboot.common.result.Result;
import com.yinhui.springboot.common.security.CurrentUser;
import com.yinhui.springboot.wms.dto.MasterRequests;
import com.yinhui.springboot.wms.dto.PageResult;
import com.yinhui.springboot.wms.entity.WmsWarehouse;
import com.yinhui.springboot.wms.service.WarehouseService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wms/warehouses")
public class WarehouseController {

    private final WarehouseService warehouseService;

    public WarehouseController(WarehouseService warehouseService) {
        this.warehouseService = warehouseService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('wms:warehouse:list')")
    public Result<PageResult<WmsWarehouse>> list(@RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status) {
        return Result.success(warehouseService.listWarehouses(keyword, status,
                PageResult.safePage(page), PageResult.safeSize(size)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:warehouse:list')")
    public Result<WmsWarehouse> detail(@PathVariable long id) {
        return Result.success(warehouseService.getWarehouse(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('wms:warehouse:add')")
    public Result<Map<String, Long>> create(@Valid @RequestBody MasterRequests.Warehouse request,
            @AuthenticationPrincipal CurrentUser principal) {
        long id = warehouseService.createWarehouse(request, principal.user().id());
        return Result.success(Map.of("id", id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:warehouse:edit')")
    public Result<Void> update(@PathVariable long id, @Valid @RequestBody MasterRequests.Warehouse request,
            @AuthenticationPrincipal CurrentUser principal) {
        warehouseService.updateWarehouse(id, request, principal.user().id());
        return Result.success(null);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:warehouse:remove')")
    public Result<Void> delete(@PathVariable long id) {
        warehouseService.deleteWarehouse(id);
        return Result.success(null);
    }
}
