package com.yinhui.springboot.wms.controller;

import com.yinhui.springboot.common.result.Result;
import com.yinhui.springboot.common.security.CurrentUser;
import com.yinhui.springboot.wms.dto.MasterRequests;
import com.yinhui.springboot.wms.dto.MasterViews;
import com.yinhui.springboot.wms.service.WarehouseService;
import jakarta.validation.Valid;
import java.util.List;
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
@RequestMapping("/api/wms/areas")
public class AreaController {

    private final WarehouseService warehouseService;

    public AreaController(WarehouseService warehouseService) {
        this.warehouseService = warehouseService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('wms:area:list')")
    public Result<List<MasterViews.AreaRow>> list(@RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) String keyword) {
        return Result.success(warehouseService.listAreas(warehouseId, keyword));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('wms:area:add')")
    public Result<Map<String, Long>> create(@Valid @RequestBody MasterRequests.Area request,
            @AuthenticationPrincipal CurrentUser principal) {
        return Result.success(Map.of("id", warehouseService.createArea(request, principal.user().id())));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:area:edit')")
    public Result<Void> update(@PathVariable long id, @Valid @RequestBody MasterRequests.Area request,
            @AuthenticationPrincipal CurrentUser principal) {
        warehouseService.updateArea(id, request, principal.user().id());
        return Result.success(null);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:area:remove')")
    public Result<Void> delete(@PathVariable long id) {
        warehouseService.deleteArea(id);
        return Result.success(null);
    }
}
