package com.yinhui.springboot.wms.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 六类作业单据的请求体。数量统一三位小数、单价四位小数，与数据库精度一致。
 */
public final class OrderRequests {

    private OrderRequests() {
    }

    public record InboundItem(
            @NotNull(message = "商品不能为空") Long productId,
            @NotNull(message = "库位不能为空") Long locationId,
            @Size(max = 64, message = "批次号最多 64 个字符") String batchNo,
            LocalDate productionDate,
            LocalDate expireDate,
            @NotNull(message = "数量不能为空")
            @DecimalMin(value = "0.001", message = "数量必须大于 0")
            @Digits(integer = 15, fraction = 3, message = "数量最多三位小数") BigDecimal quantity,
            @DecimalMin(value = "0", message = "单价不能为负数")
            @Digits(integer = 14, fraction = 4, message = "单价最多四位小数") BigDecimal unitPrice) {
    }

    public record Inbound(
            @NotNull(message = "仓库不能为空") Long warehouseId,
            @NotNull(message = "供应商不能为空") Long supplierId,
            @Size(max = 500, message = "备注最多 500 个字符") String remark,
            @NotEmpty(message = "请至少录入一条明细") @Size(max = 50, message = "单张单据最多 50 条明细")
            List<@Valid InboundItem> items) {
    }

    public record OutboundItem(
            @NotNull(message = "商品不能为空") Long productId,
            @NotNull(message = "库位不能为空") Long locationId,
            @Size(max = 64, message = "批次号最多 64 个字符") String batchNo,
            @NotNull(message = "数量不能为空")
            @DecimalMin(value = "0.001", message = "数量必须大于 0")
            @Digits(integer = 15, fraction = 3, message = "数量最多三位小数") BigDecimal quantity,
            @DecimalMin(value = "0", message = "单价不能为负数")
            @Digits(integer = 14, fraction = 4, message = "单价最多四位小数") BigDecimal unitPrice) {
    }

    public record Outbound(
            @NotNull(message = "仓库不能为空") Long warehouseId,
            Long customerId,
            @Size(max = 500, message = "备注最多 500 个字符") String remark,
            @NotEmpty(message = "请至少录入一条明细") @Size(max = 50, message = "单张单据最多 50 条明细")
            List<@Valid OutboundItem> items) {
    }

    public record FreezeItem(
            @NotNull(message = "商品不能为空") Long productId,
            @NotNull(message = "库位不能为空") Long locationId,
            @Size(max = 64, message = "批次号最多 64 个字符") String batchNo,
            @NotBlank(message = "操作类型不能为空")
            @Pattern(regexp = "FREEZE|UNFREEZE", message = "操作类型只能为冻结或解冻") String opType,
            @NotNull(message = "数量不能为空")
            @DecimalMin(value = "0.001", message = "数量必须大于 0")
            @Digits(integer = 15, fraction = 3, message = "数量最多三位小数") BigDecimal quantity,
            @Size(max = 500, message = "原因最多 500 个字符") String reason) {
    }

    public record Freeze(
            @NotNull(message = "仓库不能为空") Long warehouseId,
            @Size(max = 500, message = "备注最多 500 个字符") String remark,
            @NotEmpty(message = "请至少录入一条明细") @Size(max = 50, message = "单张单据最多 50 条明细")
            List<@Valid FreezeItem> items) {
    }

    public record TransferItem(
            @NotNull(message = "商品不能为空") Long productId,
            @NotNull(message = "源库位不能为空") Long fromLocationId,
            @NotNull(message = "目标库位不能为空") Long toLocationId,
            @Size(max = 64, message = "批次号最多 64 个字符") String batchNo,
            @NotNull(message = "数量不能为空")
            @DecimalMin(value = "0.001", message = "数量必须大于 0")
            @Digits(integer = 15, fraction = 3, message = "数量最多三位小数") BigDecimal quantity) {
    }

    public record Transfer(
            @NotNull(message = "源仓库不能为空") Long fromWarehouseId,
            @NotNull(message = "目标仓库不能为空") Long toWarehouseId,
            @Size(max = 500, message = "备注最多 500 个字符") String remark,
            @NotEmpty(message = "请至少录入一条明细") @Size(max = 50, message = "单张单据最多 50 条明细")
            List<@Valid TransferItem> items) {
    }

    public record Stocktake(
            @NotNull(message = "仓库不能为空") Long warehouseId,
            Long areaId,
            @Size(max = 500, message = "备注最多 500 个字符") String remark) {
    }

    public record StocktakeCountItem(
            @NotNull(message = "盘点明细不能为空") Long itemId,
            @NotNull(message = "实盘数量不能为空")
            @DecimalMin(value = "0", message = "实盘数量不能为负数")
            @Digits(integer = 15, fraction = 3, message = "实盘数量最多三位小数") BigDecimal actualQty) {
    }

    public record StocktakeCount(
            @NotEmpty(message = "请至少录入一条盘点结果") @Size(max = 500, message = "单次最多录入 500 条")
            List<@Valid StocktakeCountItem> items) {
    }

    public record AdjustItem(
            @NotNull(message = "商品不能为空") Long productId,
            @NotNull(message = "库位不能为空") Long locationId,
            @Size(max = 64, message = "批次号最多 64 个字符") String batchNo,
            @NotBlank(message = "调整类型不能为空")
            @Pattern(regexp = "GAIN|LOSS", message = "调整类型只能为盘盈或盘亏") String adjustType,
            @NotNull(message = "调整数量不能为空")
            @DecimalMin(value = "0.001", message = "调整数量必须大于 0")
            @Digits(integer = 15, fraction = 3, message = "调整数量最多三位小数") BigDecimal adjustQty,
            @Size(max = 500, message = "原因最多 500 个字符") String reason) {
    }

    public record Adjust(
            @NotNull(message = "仓库不能为空") Long warehouseId,
            Long stocktakeOrderId,
            @Size(max = 500, message = "备注最多 500 个字符") String remark,
            @NotEmpty(message = "请至少录入一条明细") @Size(max = 50, message = "单张单据最多 50 条明细")
            List<@Valid AdjustItem> items) {
    }

    public record Reject(
            @NotBlank(message = "请填写驳回原因") @Size(max = 500, message = "驳回原因最多 500 个字符") String reason) {
    }
}
