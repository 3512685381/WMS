package com.yinhui.springboot.wms.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yinhui.springboot.common.exception.ApiException;
import com.yinhui.springboot.wms.dto.MasterRequests;
import com.yinhui.springboot.wms.dto.MasterViews;
import com.yinhui.springboot.wms.dto.PageResult;
import com.yinhui.springboot.wms.entity.WmsCategory;
import com.yinhui.springboot.wms.entity.WmsProduct;
import com.yinhui.springboot.wms.entity.WmsSupplier;
import com.yinhui.springboot.wms.mapper.WmsCategoryMapper;
import com.yinhui.springboot.wms.mapper.WmsProductMapper;
import com.yinhui.springboot.wms.mapper.WmsQueryMapper;
import com.yinhui.springboot.wms.mapper.WmsSupplierMapper;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 商品档案。SKU 唯一；已有库存后不允许修改批次管理、计量单位或 SKU 编码。
 */
@Service
public class ProductService {

    private static final BigDecimal ZERO = new BigDecimal("0");

    private final WmsProductMapper productMapper;
    private final WmsCategoryMapper categoryMapper;
    private final WmsSupplierMapper supplierMapper;
    private final WmsQueryMapper queryMapper;
    private final ReferenceGuard guard;

    public ProductService(WmsProductMapper productMapper, WmsCategoryMapper categoryMapper,
            WmsSupplierMapper supplierMapper, WmsQueryMapper queryMapper, ReferenceGuard guard) {
        this.productMapper = productMapper;
        this.categoryMapper = categoryMapper;
        this.supplierMapper = supplierMapper;
        this.queryMapper = queryMapper;
        this.guard = guard;
    }

    public PageResult<MasterViews.ProductRow> list(Long categoryId, Integer batchManaged, String keyword,
            long page, long size) {
        return PageResult.of(queryMapper.selectProducts(new Page<>(page, size, true), categoryId,
                batchManaged, trim(keyword)));
    }

    public MasterViews.ProductRow detail(long id) {
        MasterViews.ProductRow row = queryMapper.selectProduct(id);
        if (row == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "商品不存在");
        }
        return row;
    }

    @Transactional
    public long create(MasterRequests.Product request, long userId) {
        String sku = request.skuCode().strip();
        ensureSku(sku, null);
        validateReferences(request);
        validateStockRange(request);
        WmsProduct product = new WmsProduct();
        product.setSkuCode(sku);
        product.setName(request.name().strip());
        product.setCategoryId(request.categoryId());
        product.setSpec(trim(request.spec()));
        product.setUnit(request.unit().strip());
        product.setBatchManaged(request.batchManaged() == null ? 1 : request.batchManaged());
        product.setMinStock(request.minStock() == null ? ZERO : request.minStock());
        product.setMaxStock(request.maxStock() == null ? ZERO : request.maxStock());
        product.setShelfLifeDays(request.shelfLifeDays() == null ? 0L : request.shelfLifeDays());
        product.setDefaultSupplierId(request.defaultSupplierId());
        product.setReferencePrice(request.referencePrice() == null ? ZERO : request.referencePrice());
        product.setCreatedBy(userId);
        productMapper.insert(product);
        return product.getId();
    }

    @Transactional
    public void update(long id, MasterRequests.Product request, long userId) {
        WmsProduct product = requireProduct(id);
        String sku = request.skuCode().strip();
        boolean hasInventory = guard.exists("wms_inventory", "product_id", id);
        if (!sku.equals(product.getSkuCode())) {
            if (isReferenced(id)) {
                throw new ApiException(HttpStatus.CONFLICT, "商品已被库存或单据引用，不能修改 SKU 编码");
            }
            ensureSku(sku, id);
        }
        int batchManaged = request.batchManaged() == null ? product.getBatchManaged() : request.batchManaged();
        if (hasInventory && batchManaged != product.getBatchManaged()) {
            throw new ApiException(HttpStatus.CONFLICT, "商品已有库存，不能修改批次管理方式");
        }
        String unit = request.unit().strip();
        if (hasInventory && !unit.equals(product.getUnit())) {
            throw new ApiException(HttpStatus.CONFLICT, "商品已有库存，不能修改计量单位");
        }
        validateReferences(request);
        validateStockRange(request);
        productMapper.update(null, Wrappers.<WmsProduct>lambdaUpdate()
                .eq(WmsProduct::getId, id)
                .set(WmsProduct::getSkuCode, sku)
                .set(WmsProduct::getName, request.name().strip())
                .set(WmsProduct::getCategoryId, request.categoryId())
                .set(WmsProduct::getSpec, trim(request.spec()))
                .set(WmsProduct::getUnit, unit)
                .set(WmsProduct::getBatchManaged, batchManaged)
                .set(WmsProduct::getMinStock, request.minStock() == null ? product.getMinStock() : request.minStock())
                .set(WmsProduct::getMaxStock, request.maxStock() == null ? product.getMaxStock() : request.maxStock())
                .set(WmsProduct::getShelfLifeDays,
                        request.shelfLifeDays() == null ? product.getShelfLifeDays() : request.shelfLifeDays())
                .set(WmsProduct::getDefaultSupplierId, request.defaultSupplierId())
                .set(WmsProduct::getReferencePrice,
                        request.referencePrice() == null ? product.getReferencePrice() : request.referencePrice()));
    }

    @Transactional
    public void delete(long id) {
        requireProduct(id);
        if (isReferenced(id)) {
            throw new ApiException(HttpStatus.CONFLICT, "商品已被库存或业务单据引用，无法删除");
        }
        productMapper.deleteById(id);
    }

    private boolean isReferenced(long id) {
        return guard.exists("wms_inventory", "product_id", id)
                || guard.exists("wms_inbound_item", "product_id", id)
                || guard.exists("wms_outbound_item", "product_id", id)
                || guard.exists("wms_freeze_item", "product_id", id)
                || guard.exists("wms_transfer_item", "product_id", id)
                || guard.exists("wms_stocktake_item", "product_id", id)
                || guard.exists("wms_adjust_item", "product_id", id);
    }

    private void ensureSku(String sku, Long excludeId) {
        Long count = productMapper.selectCount(Wrappers.<WmsProduct>lambdaQuery()
                .eq(WmsProduct::getSkuCode, sku)
                .ne(excludeId != null, WmsProduct::getId, excludeId));
        if (count != null && count > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "SKU 编码已存在：" + sku);
        }
    }

    private void validateReferences(MasterRequests.Product request) {
        if (request.categoryId() != null) {
            WmsCategory category = categoryMapper.selectById(request.categoryId());
            if (category == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "商品分类不存在：" + request.categoryId());
            }
        }
        if (request.defaultSupplierId() != null) {
            WmsSupplier supplier = supplierMapper.selectById(request.defaultSupplierId());
            if (supplier == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "默认供应商不存在：" + request.defaultSupplierId());
            }
        }
    }

    private static void validateStockRange(MasterRequests.Product request) {
        BigDecimal min = request.minStock() == null ? ZERO : request.minStock();
        BigDecimal max = request.maxStock() == null ? ZERO : request.maxStock();
        if (max.signum() > 0 && max.compareTo(min) < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "库存上限不能小于安全库存");
        }
    }

    private WmsProduct requireProduct(long id) {
        WmsProduct product = productMapper.selectById(id);
        if (product == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "商品不存在");
        }
        return product;
    }

    private static String trim(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }
}
