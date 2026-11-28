package com.yinhui.springboot.wms.controller;

import com.yinhui.springboot.common.result.Result;
import com.yinhui.springboot.common.security.CurrentUser;
import com.yinhui.springboot.wms.dto.OrderRequests;
import com.yinhui.springboot.wms.dto.OrderViews;
import com.yinhui.springboot.wms.dto.PageResult;
import com.yinhui.springboot.wms.service.OutboundOrderService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
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
@RequestMapping("/api/wms/outbound-orders")
public class OutboundOrderController {

    private final OutboundOrderService orderService;

    public OutboundOrderController(OutboundOrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('wms:outbound:list')")
    public Result<PageResult<OrderViews.OutboundOrderRow>> list(@RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size, @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Long warehouseId, @RequestParam(required = false) String source,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        return Result.success(orderService.list(status, warehouseId, source, keyword, dateFrom, dateTo,
                PageResult.safePage(page), PageResult.safeSize(size)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:outbound:list')")
    public Result<OrderViews.OutboundDetail> detail(@PathVariable long id) {
        return Result.success(orderService.detail(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('wms:outbound:add')")
    public Result<OrderViews.CreatedOrder> create(@Valid @RequestBody OrderRequests.Outbound request,
            @AuthenticationPrincipal CurrentUser principal) {
        return Result.success(orderService.create(request, principal.user().id()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:outbound:edit')")
    public Result<Void> update(@PathVariable long id, @Valid @RequestBody OrderRequests.Outbound request,
            @AuthenticationPrincipal CurrentUser principal) {
        orderService.update(id, request, principal.user().id());
        return Result.success(null);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:outbound:remove')")
    public Result<Void> delete(@PathVariable long id) {
        orderService.delete(id);
        return Result.success(null);
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('wms:outbound:submit')")
    public Result<Void> submit(@PathVariable long id, @AuthenticationPrincipal CurrentUser principal) {
        orderService.submit(id, principal.user().id());
        return Result.success(null);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('wms:outbound:approve')")
    public Result<Void> approve(@PathVariable long id, @AuthenticationPrincipal CurrentUser principal) {
        orderService.approve(id, principal.user().id());
        return Result.success(null);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('wms:outbound:reject')")
    public Result<Void> reject(@PathVariable long id, @Valid @RequestBody OrderRequests.Reject request,
            @AuthenticationPrincipal CurrentUser principal) {
        orderService.reject(id, request.reason(), principal.user().id());
        return Result.success(null);
    }
}
