package com.yinhui.springboot.wms.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 单据查询视图。字段与 WmsQueryMapper 的列别名一一对应。
 */
public final class OrderViews {

    private OrderViews() {
    }

    @Getter
    @Setter
    public static class InboundOrderRow {
        private Long id;
        private String orderNo;
        private Long warehouseId;
        private String warehouseName;
        private Long supplierId;
        private String supplierName;
        private String source;
        private Integer status;
        private String statusText;
        private String remark;
        private LocalDateTime submittedAt;
        private Long reviewedBy;
        private String reviewerName;
        private LocalDateTime reviewedAt;
        private String rejectReason;
        private LocalDateTime postedAt;
        private LocalDateTime createdAt;
        private Long createdBy;
        private String creatorName;
        private Integer itemCount;
        private BigDecimal totalQuantity;
        private BigDecimal totalAmount;
    }

    @Getter
    @Setter
    public static class InboundItemRow {
        private Long id;
        private Long productId;
        private String skuCode;
        private String productName;
        private String unit;
        private Long locationId;
        private String locationCode;
        private String batchNo;
        private LocalDate productionDate;
        private LocalDate expireDate;
        private BigDecimal quantity;
        private BigDecimal unitPrice;
        private BigDecimal amount;
    }

    @Getter
    @Setter
    public static class OutboundOrderRow {
        private Long id;
        private String orderNo;
        private Long warehouseId;
        private String warehouseName;
        private Long customerId;
        private String customerName;
        private String source;
        private Integer status;
        private String statusText;
        private String remark;
        private LocalDateTime submittedAt;
        private Long reviewedBy;
        private String reviewerName;
        private LocalDateTime reviewedAt;
        private String rejectReason;
        private LocalDateTime postedAt;
        private LocalDateTime createdAt;
        private Long createdBy;
        private String creatorName;
        private Integer itemCount;
        private BigDecimal totalQuantity;
        private BigDecimal totalAmount;
    }

    @Getter
    @Setter
    public static class OutboundItemRow {
        private Long id;
        private Long productId;
        private String skuCode;
        private String productName;
        private String unit;
        private Long locationId;
        private String locationCode;
        private String batchNo;
        private BigDecimal quantity;
        private BigDecimal unitPrice;
        private BigDecimal amount;
    }

    @Getter
    @Setter
    public static class FreezeOrderRow {
        private Long id;
        private String orderNo;
        private Long warehouseId;
        private String warehouseName;
        private Integer status;
        private String statusText;
        private String remark;
        private LocalDateTime submittedAt;
        private Long reviewedBy;
        private String reviewerName;
        private LocalDateTime reviewedAt;
        private String rejectReason;
        private LocalDateTime postedAt;
        private LocalDateTime createdAt;
        private Long createdBy;
        private String creatorName;
        private Integer itemCount;
        private BigDecimal totalQuantity;
    }

    @Getter
    @Setter
    public static class FreezeItemRow {
        private Long id;
        private Long productId;
        private String skuCode;
        private String productName;
        private String unit;
        private Long locationId;
        private String locationCode;
        private String batchNo;
        private String opType;
        private BigDecimal quantity;
        private String reason;
    }

    @Getter
    @Setter
    public static class TransferOrderRow {
        private Long id;
        private String orderNo;
        private Long fromWarehouseId;
        private String fromWarehouseName;
        private Long toWarehouseId;
        private String toWarehouseName;
        private Integer status;
        private String statusText;
        private String remark;
        private LocalDateTime submittedAt;
        private Long reviewedBy;
        private String reviewerName;
        private LocalDateTime reviewedAt;
        private String rejectReason;
        private LocalDateTime postedAt;
        private LocalDateTime createdAt;
        private Long createdBy;
        private String creatorName;
        private Integer itemCount;
        private BigDecimal totalQuantity;
    }

    @Getter
    @Setter
    public static class TransferItemRow {
        private Long id;
        private Long productId;
        private String skuCode;
        private String productName;
        private String unit;
        private Long fromLocationId;
        private String fromLocationCode;
        private Long toLocationId;
        private String toLocationCode;
        private String batchNo;
        private BigDecimal quantity;
    }

    @Getter
    @Setter
    public static class StocktakeOrderRow {
        private Long id;
        private String orderNo;
        private Long warehouseId;
        private String warehouseName;
        private Integer status;
        private String statusText;
        private String remark;
        private LocalDateTime submittedAt;
        private Long reviewedBy;
        private String reviewerName;
        private LocalDateTime reviewedAt;
        private String rejectReason;
        private LocalDateTime postedAt;
        private LocalDateTime createdAt;
        private Long createdBy;
        private String creatorName;
        private Integer itemCount;
        private Integer countedCount;
        private Integer diffCount;
        private BigDecimal totalDiff;
    }

    @Getter
    @Setter
    public static class StocktakeItemRow {
        private Long id;
        private Long productId;
        private String skuCode;
        private String productName;
        private String unit;
        private Long locationId;
        private String locationCode;
        private String batchNo;
        private BigDecimal bookQty;
        private BigDecimal actualQty;
        private BigDecimal diffQty;
        private Long inventoryVersion;
    }

    @Getter
    @Setter
    public static class AdjustOrderRow {
        private Long id;
        private String orderNo;
        private Long warehouseId;
        private String warehouseName;
        private Long stocktakeOrderId;
        private String stocktakeOrderNo;
        private Integer status;
        private String statusText;
        private String remark;
        private LocalDateTime submittedAt;
        private Long reviewedBy;
        private String reviewerName;
        private LocalDateTime reviewedAt;
        private String rejectReason;
        private LocalDateTime postedAt;
        private LocalDateTime createdAt;
        private Long createdBy;
        private String creatorName;
        private Integer itemCount;
        private BigDecimal totalQuantity;
    }

    @Getter
    @Setter
    public static class AdjustItemRow {
        private Long id;
        private Long productId;
        private String skuCode;
        private String productName;
        private String unit;
        private Long locationId;
        private String locationCode;
        private String batchNo;
        private String adjustType;
        private BigDecimal adjustQty;
        private String reason;
    }

    public record CreatedOrder(Long id, String orderNo, Integer status) {
    }

    public record InboundDetail(InboundOrderRow order, List<InboundItemRow> items) {
    }

    public record OutboundDetail(OutboundOrderRow order, List<OutboundItemRow> items) {
    }

    public record FreezeDetail(FreezeOrderRow order, List<FreezeItemRow> items) {
    }

    public record TransferDetail(TransferOrderRow order, List<TransferItemRow> items) {
    }

    public record StocktakeDetail(StocktakeOrderRow order, List<StocktakeItemRow> items) {
    }

    public record AdjustDetail(AdjustOrderRow order, List<AdjustItemRow> items) {
    }
}
