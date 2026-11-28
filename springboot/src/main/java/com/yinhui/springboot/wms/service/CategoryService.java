package com.yinhui.springboot.wms.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yinhui.springboot.common.exception.ApiException;
import com.yinhui.springboot.wms.dto.MasterRequests;
import com.yinhui.springboot.wms.dto.MasterViews;
import com.yinhui.springboot.wms.entity.WmsCategory;
import com.yinhui.springboot.wms.mapper.WmsCategoryMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商品分类树。删除前检查子分类和商品引用，移动父节点时禁止形成环。
 */
@Service
public class CategoryService {

    private final WmsCategoryMapper categoryMapper;
    private final ReferenceGuard guard;

    public CategoryService(WmsCategoryMapper categoryMapper, ReferenceGuard guard) {
        this.categoryMapper = categoryMapper;
        this.guard = guard;
    }

    public List<MasterViews.CategoryNode> tree() {
        List<WmsCategory> categories = categoryMapper.selectList(Wrappers.<WmsCategory>lambdaQuery()
                .orderByAsc(WmsCategory::getSortOrder).orderByAsc(WmsCategory::getId));
        Map<Long, List<WmsCategory>> children = new HashMap<>();
        for (WmsCategory category : categories) {
            children.computeIfAbsent(category.getParentId(), ignored -> new ArrayList<>()).add(category);
        }
        return build(children, null);
    }

    @Transactional
    public long create(MasterRequests.Category request, long userId) {
        if (request.parentId() != null) {
            requireCategory(request.parentId());
        }
        WmsCategory category = new WmsCategory();
        category.setParentId(request.parentId());
        category.setName(request.name().strip());
        category.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        category.setCreatedBy(userId);
        categoryMapper.insert(category);
        return category.getId();
    }

    @Transactional
    public void update(long id, MasterRequests.Category request, long userId) {
        WmsCategory category = requireCategory(id);
        Long parentId = request.parentId();
        if (parentId != null) {
            if (parentId == id) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "分类不能作为自己的父级");
            }
            WmsCategory parent = requireCategory(parentId);
            while (parent != null) {
                if (parent.getId() == id) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "不能移动到自己的子分类下");
                }
                parent = parent.getParentId() == null ? null : categoryMapper.selectById(parent.getParentId());
            }
        }
        categoryMapper.update(null, Wrappers.<WmsCategory>lambdaUpdate()
                .eq(WmsCategory::getId, id)
                .set(WmsCategory::getParentId, parentId)
                .set(WmsCategory::getName, request.name().strip())
                .set(WmsCategory::getSortOrder, request.sortOrder() == null ? category.getSortOrder() : request.sortOrder()));
    }

    @Transactional
    public void delete(long id) {
        requireCategory(id);
        guard.requireUnused(id, "分类下已有子分类或商品，无法删除",
                "wms_category.parent_id", "wms_product.category_id");
        categoryMapper.deleteById(id);
    }

    private WmsCategory requireCategory(long id) {
        WmsCategory category = categoryMapper.selectById(id);
        if (category == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "分类不存在：" + id);
        }
        return category;
    }

    private static List<MasterViews.CategoryNode> build(Map<Long, List<WmsCategory>> children, Long parentId) {
        return children.getOrDefault(parentId, List.of()).stream()
                .map(category -> new MasterViews.CategoryNode(category.getId(), category.getParentId(),
                        category.getName(), category.getSortOrder(), build(children, category.getId())))
                .toList();
    }
}
