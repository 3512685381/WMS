package com.yinhui.springboot.wms.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 基础资料与库存查询视图。
 */
public final class MasterViews {

    private MasterViews() {
    }

    @Getter
    @Setter
    public static class AreaRow {
        private Long id;
        private Long warehouseId;
        private String warehouseName;
        private String code;
        private String name;
        private String areaType;
    }

    @Getter
    @Setter
    public static class LocationRow {
        private Long id;
        private Long warehouseId;
        private String warehouseName;
        private Long areaId;
        private String areaName;
        private String code;
        private BigDecimal maxCapacity;
        private Integer status;
    }

    @Getter
    @Setter
    public static class ProductRow {
        private Long id;
        private String skuCode;
        private String name;
        private Long categoryId;
        private String categoryName;
        private String spec;
        private String unit;
        private Integer batchManaged;
        private BigDecimal minStock;
        private BigDecimal maxStock;
        private Long shelfLifeDays;
        private Long defaultSupplierId;
        private String supplierName;
        private BigDecimal referencePrice;
        private LocalDateTime updatedAt;
    }

    @Getter
    @Setter
    public static class PartnerRow {
        private Long id;
        private String code;
        private String name;
        private String contact;
        private String phone;
        private String address;
        private LocalDateTime updatedAt;
    }

    @Getter
    @Setter
    public static class InventoryRow {
        private Long id;
        private Long warehouseId;
        private String warehouseName;
        private Long locationId;
        private String locationCode;
        private Long productId;
        private String skuCode;
        private String productName;
        private String unit;
        private String batchNo;
        private LocalDate productionDate;
        private LocalDate expireDate;
        private BigDecimal quantity;
        private BigDecimal frozenQuantity;
        private BigDecimal availableQuantity;
        private Long version;
        private LocalDateTime updatedAt;
    }

    @Getter
    @Setter
    public static class InventoryLogRow {
        private Long id;
        private Long inventoryId;
        private Long warehouseId;
        private String warehouseName;
        private Long locationId;
        private String locationCode;
        private Long productId;
        private String skuCode;
        private String productName;
        private String unit;
        private String batchNo;
        private String bizType;
        private String orderNo;
        private BigDecimal beforeQty;
        private BigDecimal changeQty;
        private BigDecimal afterQty;
        private BigDecimal beforeFrozenQty;
        private BigDecimal changeFrozenQty;
        private BigDecimal afterFrozenQty;
        private Long operatorId;
        private String operatorName;
        private LocalDateTime createdAt;
    }

    @Getter
    @Setter
    public static class LowStockRow {
        private Long warehouseId;
        private String warehouseName;
        private Long productId;
        private String skuCode;
        private String productName;
        private String unit;
        private BigDecimal quantity;
        private BigDecimal frozenQuantity;
        private BigDecimal availableQuantity;
        private BigDecimal minStock;
        private BigDecimal shortage;
        private Boolean noStockRecord;
    }

    @Getter
    @Setter
    public static class OverstockRow {
        private Long warehouseId;
        private String warehouseName;
        private Long productId;
        private String skuCode;
        private String productName;
        private String unit;
        private BigDecimal quantity;
        private BigDecimal frozenQuantity;
        private BigDecimal availableQuantity;
        private BigDecimal maxStock;
        private BigDecimal excess;
    }

    @Getter
    @Setter
    public static class ExpiringRow {
        private Long inventoryId;
        private Long warehouseId;
        private String warehouseName;
        private Long locationId;
        private String locationCode;
        private Long productId;
        private String skuCode;
        private String productName;
        private String unit;
        private String batchNo;
        private LocalDate expireDate;
        private BigDecimal quantity;
        private BigDecimal frozenQuantity;
        private BigDecimal availableQuantity;
        private Long daysLeft;
    }

    @Getter
    @Setter
    public static class DashboardSummary {
        private Long warehouseCount;
        private Long productCount;
        private BigDecimal totalQuantity;
        private BigDecimal totalFrozenQuantity;
        private BigDecimal inventoryValue;
        private Long pendingInbound;
        private Long pendingOutbound;
        private Long lowStockCount;
        private Long expiringCount;
    }

    public record CategoryNode(Long id, Long parentId, String name, Integer sortOrder, List<CategoryNode> children) {
    }
}
