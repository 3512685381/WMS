package com.yinhui.springboot.wms.service;

import com.yinhui.springboot.common.exception.ApiException;
import com.yinhui.springboot.wms.entity.WmsCustomer;
import com.yinhui.springboot.wms.entity.WmsLocation;
import com.yinhui.springboot.wms.entity.WmsProduct;
import com.yinhui.springboot.wms.entity.WmsSupplier;
import com.yinhui.springboot.wms.entity.WmsWarehouse;
import com.yinhui.springboot.wms.mapper.WmsCustomerMapper;
import com.yinhui.springboot.wms.mapper.WmsLocationMapper;
import com.yinhui.springboot.wms.mapper.WmsProductMapper;
import com.yinhui.springboot.wms.mapper.WmsSupplierMapper;
import com.yinhui.springboot.wms.mapper.WmsWarehouseMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 单据落库前的档案与批次校验，统一 Java 人工建单和 FastAPI 代建草稿之后的审核口径。
 */
@Component
public class WmsValidator {

    private static final BigDecimal ZERO_MONEY = new BigDecimal("0.0000");
    private static final String NO_BATCH = "NO_BATCH";

    private final WmsWarehouseMapper warehouseMapper;
    private final WmsLocationMapper locationMapper;
    private final WmsProductMapper productMapper;
    private final WmsSupplierMapper supplierMapper;
    private final WmsCustomerMapper customerMapper;

    public WmsValidator(WmsWarehouseMapper warehouseMapper, WmsLocationMapper locationMapper,
            WmsProductMapper productMapper, WmsSupplierMapper supplierMapper,
            WmsCustomerMapper customerMapper) {
        this.warehouseMapper = warehouseMapper;
        this.locationMapper = locationMapper;
        this.productMapper = productMapper;
        this.supplierMapper = supplierMapper;
        this.customerMapper = customerMapper;
    }

    public record ItemRules(String batchNo, LocalDate productionDate, LocalDate expireDate) {
    }

    public WmsWarehouse requireActiveWarehouse(long warehouseId) {
        WmsWarehouse warehouse = warehouseMapper.selectById(warehouseId);
        if (warehouse == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "仓库不存在");
        }
        if (!Integer.valueOf(1).equals(warehouse.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "仓库已停用，不能新建或审核单据");
        }
        return warehouse;
    }

    public WmsLocation requireLocation(long warehouseId, long locationId) {
        WmsLocation location = locationMapper.selectById(locationId);
        if (location == null || location.getWarehouseId() != warehouseId) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "库位不存在或不属于当前仓库：" + locationId);
        }
        if (!Integer.valueOf(1).equals(location.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "库位已停用：" + location.getCode());
        }
        return location;
    }

    public WmsProduct requireProduct(long productId) {
        WmsProduct product = productMapper.selectById(productId);
        if (product == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "商品不存在：" + productId);
        }
        return product;
    }

    public void requireSupplier(Long supplierId) {
        if (supplierId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "入库单必须指定供应商");
        }
        WmsSupplier supplier = supplierMapper.selectById(supplierId);
        if (supplier == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "供应商不存在：" + supplierId);
        }
    }

    public void requireCustomer(Long customerId) {
        if (customerId == null) {
            return;
        }
        WmsCustomer customer = customerMapper.selectById(customerId);
        if (customer == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "客户不存在：" + customerId);
        }
    }

    /**
     * 批次规则：批次管理的商品必须填写实际批次；非批次管理统一写 NO_BATCH。
     * 生产日期与效期按商品保质期补齐，效期不得早于生产日期。
     */
    public ItemRules inboundRules(WmsProduct product, String batchNo, LocalDate productionDate,
            LocalDate expireDate) {
        String normalized = normalizeBatch(product, batchNo);
        LocalDate expiry = expireDate;
        if (expiry == null && productionDate != null && product.getShelfLifeDays() != null
                && product.getShelfLifeDays() > 0) {
            expiry = productionDate.plusDays(product.getShelfLifeDays());
        }
        if (productionDate != null && expiry != null && expiry.isBefore(productionDate)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "效期不得早于生产日期：" + product.getName());
        }
        return new ItemRules(normalized, productionDate, expiry);
    }

    public String normalizeBatch(WmsProduct product, String batchNo) {
        String value = batchNo == null ? "" : batchNo.strip();
        if (Integer.valueOf(1).equals(product.getBatchManaged())) {
            if (value.isEmpty() || NO_BATCH.equalsIgnoreCase(value)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "商品须填写实际批次号：" + product.getName());
            }
            return value;
        }
        if (!value.isEmpty() && !NO_BATCH.equalsIgnoreCase(value)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "商品不按批次管理：" + product.getName());
        }
        return NO_BATCH;
    }

    public static BigDecimal money(BigDecimal value) {
        if (value == null) {
            return ZERO_MONEY;
        }
        try {
            return value.setScale(4, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException error) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "单价最多四位小数");
        }
    }
}
