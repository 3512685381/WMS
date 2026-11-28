package com.yinhui.springboot.wms.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 基础资料的请求体。编码、计量单位和批次规则在服务层做唯一性与引用校验。
 */
public final class MasterRequests {

    private MasterRequests() {
    }

    public record Warehouse(
            @NotBlank(message = "仓库编码不能为空") @Size(max = 32, message = "仓库编码最多 32 个字符") String code,
            @NotBlank(message = "仓库名称不能为空") @Size(max = 128, message = "仓库名称最多 128 个字符") String name,
            @Size(max = 255, message = "地址最多 255 个字符") String address,
            @Size(max = 64, message = "负责人最多 64 个字符") String manager,
            @Min(value = 0, message = "仓库状态只能为 0 或 1") @Max(value = 1, message = "仓库状态只能为 0 或 1")
            Integer status) {
    }

    public record Area(
            @NotNull(message = "所属仓库不能为空") Long warehouseId,
            @NotBlank(message = "库区编码不能为空") @Size(max = 32, message = "库区编码最多 32 个字符") String code,
            @NotBlank(message = "库区名称不能为空") @Size(max = 128, message = "库区名称最多 128 个字符") String name,
            @NotBlank(message = "库区类型不能为空")
            @Pattern(regexp = "RECEIVING|STORAGE|PICKING|DEFECTIVE", message = "库区类型无效")
            String areaType) {
    }

    public record Location(
            @NotNull(message = "所属仓库不能为空") Long warehouseId,
            @NotNull(message = "所属库区不能为空") Long areaId,
            @NotBlank(message = "库位编码不能为空") @Size(max = 32, message = "库位编码最多 32 个字符") String code,
            @DecimalMin(value = "0", message = "容量上限不能为负数")
            @Digits(integer = 15, fraction = 3, message = "容量最多三位小数") BigDecimal maxCapacity,
            @Min(value = 0, message = "库位状态只能为 0 或 1") @Max(value = 1, message = "库位状态只能为 0 或 1")
            Integer status) {
    }

    public record Category(
            Long parentId,
            @NotBlank(message = "分类名称不能为空") @Size(max = 128, message = "分类名称最多 128 个字符") String name,
            Integer sortOrder) {
    }

    public record Partner(
            @NotBlank(message = "编码不能为空") @Size(max = 32, message = "编码最多 32 个字符") String code,
            @NotBlank(message = "名称不能为空") @Size(max = 128, message = "名称最多 128 个字符") String name,
            @Size(max = 64, message = "联系人最多 64 个字符") String contact,
            @Size(max = 32, message = "电话最多 32 个字符") String phone,
            @Size(max = 255, message = "地址最多 255 个字符") String address) {
    }

    public record Product(
            @NotBlank(message = "SKU 编码不能为空") @Size(max = 64, message = "SKU 编码最多 64 个字符") String skuCode,
            @NotBlank(message = "商品名称不能为空") @Size(max = 128, message = "商品名称最多 128 个字符") String name,
            Long categoryId,
            @Size(max = 128, message = "规格最多 128 个字符") String spec,
            @NotBlank(message = "计量单位不能为空") @Size(max = 16, message = "计量单位最多 16 个字符") String unit,
            @Min(value = 0, message = "批次管理标识只能为 0 或 1") @Max(value = 1, message = "批次管理标识只能为 0 或 1")
            Integer batchManaged,
            @DecimalMin(value = "0", message = "安全库存不能为负数")
            @Digits(integer = 15, fraction = 3, message = "安全库存最多三位小数") BigDecimal minStock,
            @DecimalMin(value = "0", message = "库存上限不能为负数")
            @Digits(integer = 15, fraction = 3, message = "库存上限最多三位小数") BigDecimal maxStock,
            @Min(value = 0, message = "保质期不能为负数") Long shelfLifeDays,
            Long defaultSupplierId,
            @DecimalMin(value = "0", message = "参考价不能为负数")
            @Digits(integer = 14, fraction = 4, message = "参考价最多四位小数") BigDecimal referencePrice) {
    }
}
