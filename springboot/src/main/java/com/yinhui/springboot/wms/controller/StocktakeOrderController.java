package com.yinhui.springboot.wms.controller;

import com.yinhui.springboot.common.result.Result;
import com.yinhui.springboot.common.security.CurrentUser;
import com.yinhui.springboot.wms.dto.OrderRequests;
import com.yinhui.springboot.wms.dto.OrderViews;
import com.yinhui.springboot.wms.dto.PageResult;
import com.yinhui.springboot.wms.service.StocktakeOrderService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wms/stocktake-orders")
public class StocktakeOrderController {

    private final StocktakeOrderService orderService;

    public StocktakeOrderController(StocktakeOrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('wms:stocktake:list')")
    public Result<PageResult<OrderViews.StocktakeOrderRow>> list(@RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size, @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Long warehouseId, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        return Result.success(orderService.list(status, warehouseId, keyword, dateFrom, dateTo,
                PageResult.safePage(page), PageResult.safeSize(size)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:stocktake:list')")
    public Result<OrderViews.StocktakeDetail> detail(@PathVariable long id) {
        return Result.success(orderService.detail(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('wms:stocktake:add')")
    public Result<OrderViews.CreatedOrder> create(@Valid @RequestBody OrderRequests.Stocktake request,
            @AuthenticationPrincipal CurrentUser principal) {
        return Result.success(orderService.create(request, principal.user().id()));
    }

    @PutMapping("/{id}/count")
    @PreAuthorize("hasAuthority('wms:stocktake:count')")
    public Result<Void> count(@PathVariable long id, @Valid @RequestBody OrderRequests.StocktakeCount request) {
        orderService.count(id, request);
        return Result.success(null);
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAuthority('wms:stocktake:complete')")
    public Result<Map<String, Long>> complete(@PathVariable long id,
            @AuthenticationPrincipal CurrentUser principal) {
        return Result.success(Map.of("adjustOrderId", orderService.complete(id, principal.user().id())));
    }
}
