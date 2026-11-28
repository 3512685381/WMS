package com.yinhui.springboot.wms.controller;

import com.yinhui.springboot.common.result.Result;
import com.yinhui.springboot.common.security.CurrentUser;
import com.yinhui.springboot.wms.dto.MasterRequests;
import com.yinhui.springboot.wms.dto.MasterViews;
import com.yinhui.springboot.wms.dto.PageResult;
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
@RequestMapping("/api/wms/locations")
public class LocationController {

    private final WarehouseService warehouseService;

    public LocationController(WarehouseService warehouseService) {
        this.warehouseService = warehouseService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('wms:location:list')")
    public Result<PageResult<MasterViews.LocationRow>> list(@RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size, @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long areaId, @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword) {
        return Result.success(warehouseService.listLocations(warehouseId, areaId, status, keyword,
                PageResult.safePage(page), PageResult.safeSize(size)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('wms:location:add')")
    public Result<Map<String, Long>> create(@Valid @RequestBody MasterRequests.Location request,
            @AuthenticationPrincipal CurrentUser principal) {
        return Result.success(Map.of("id", warehouseService.createLocation(request, principal.user().id())));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:location:edit')")
    public Result<Void> update(@PathVariable long id, @Valid @RequestBody MasterRequests.Location request,
            @AuthenticationPrincipal CurrentUser principal) {
        warehouseService.updateLocation(id, request, principal.user().id());
        return Result.success(null);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:location:remove')")
    public Result<Void> delete(@PathVariable long id) {
        warehouseService.deleteLocation(id);
        return Result.success(null);
    }
}
