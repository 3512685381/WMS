package com.yinhui.springboot.wms.controller;

import com.yinhui.springboot.common.result.Result;
import com.yinhui.springboot.common.security.CurrentUser;
import com.yinhui.springboot.wms.dto.MasterRequests;
import com.yinhui.springboot.wms.dto.MasterViews;
import com.yinhui.springboot.wms.dto.PageResult;
import com.yinhui.springboot.wms.service.ProductService;
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
@RequestMapping("/api/wms/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('wms:product:list')")
    public Result<PageResult<MasterViews.ProductRow>> list(@RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size, @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Integer batchManaged, @RequestParam(required = false) String keyword) {
        return Result.success(productService.list(categoryId, batchManaged, keyword,
                PageResult.safePage(page), PageResult.safeSize(size)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:product:list')")
    public Result<MasterViews.ProductRow> detail(@PathVariable long id) {
        return Result.success(productService.detail(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('wms:product:add')")
    public Result<Map<String, Long>> create(@Valid @RequestBody MasterRequests.Product request,
            @AuthenticationPrincipal CurrentUser principal) {
        return Result.success(Map.of("id", productService.create(request, principal.user().id())));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:product:edit')")
    public Result<Void> update(@PathVariable long id, @Valid @RequestBody MasterRequests.Product request,
            @AuthenticationPrincipal CurrentUser principal) {
        productService.update(id, request, principal.user().id());
        return Result.success(null);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:product:remove')")
    public Result<Void> delete(@PathVariable long id) {
        productService.delete(id);
        return Result.success(null);
    }
}
