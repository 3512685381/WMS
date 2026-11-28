package com.yinhui.springboot.wms.controller;

import com.yinhui.springboot.common.result.Result;
import com.yinhui.springboot.common.security.CurrentUser;
import com.yinhui.springboot.wms.dto.MasterRequests;
import com.yinhui.springboot.wms.dto.MasterViews;
import com.yinhui.springboot.wms.service.CategoryService;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wms/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('wms:category:list')")
    public Result<List<MasterViews.CategoryNode>> tree() {
        return Result.success(categoryService.tree());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('wms:category:add')")
    public Result<Map<String, Long>> create(@Valid @RequestBody MasterRequests.Category request,
            @AuthenticationPrincipal CurrentUser principal) {
        return Result.success(Map.of("id", categoryService.create(request, principal.user().id())));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:category:edit')")
    public Result<Void> update(@PathVariable long id, @Valid @RequestBody MasterRequests.Category request,
            @AuthenticationPrincipal CurrentUser principal) {
        categoryService.update(id, request, principal.user().id());
        return Result.success(null);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('wms:category:remove')")
    public Result<Void> delete(@PathVariable long id) {
        categoryService.delete(id);
        return Result.success(null);
    }
}
