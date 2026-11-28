package com.yinhui.springboot.wms.controller;

import com.yinhui.springboot.common.result.Result;
import com.yinhui.springboot.common.security.CurrentUser;
import com.yinhui.springboot.wms.dto.MasterRequests;
import com.yinhui.springboot.wms.dto.MasterViews;
import com.yinhui.springboot.wms.dto.PageResult;
import com.yinhui.springboot.wms.service.PartnerService;
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
@RequestMapping("/api/wms/suppliers")
public class SupplierController {

    private final PartnerService partnerService;

    public SupplierController(PartnerService partnerService) {
        this.partnerService = partnerService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('wms:supplier:list')")
    public Result<PageResult<MasterViews.PartnerRow>> list(@RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size, @RequestParam(required = false) String keyword) {
        return Result.success(partnerService.list(PartnerService.PartnerType.SUPPLIER, keyword,
                PageResult.safePage(page), PageResult.safeSize(size)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:supplier:list')")
    public Result<MasterViews.PartnerRow> detail(@PathVariable long id) {
        return Result.success(partnerService.detail(PartnerService.PartnerType.SUPPLIER, id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('wms:supplier:add')")
    public Result<Map<String, Long>> create(@Valid @RequestBody MasterRequests.Partner request,
            @AuthenticationPrincipal CurrentUser principal) {
        return Result.success(Map.of("id",
                partnerService.create(PartnerService.PartnerType.SUPPLIER, request, principal.user().id())));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:supplier:edit')")
    public Result<Void> update(@PathVariable long id, @Valid @RequestBody MasterRequests.Partner request,
            @AuthenticationPrincipal CurrentUser principal) {
        partnerService.update(PartnerService.PartnerType.SUPPLIER, id, request, principal.user().id());
        return Result.success(null);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:supplier:remove')")
    public Result<Void> delete(@PathVariable long id) {
        partnerService.delete(PartnerService.PartnerType.SUPPLIER, id);
        return Result.success(null);
    }
}
