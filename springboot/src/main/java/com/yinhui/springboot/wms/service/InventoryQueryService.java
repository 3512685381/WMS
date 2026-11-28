package com.yinhui.springboot.wms.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yinhui.springboot.common.exception.ApiException;
import com.yinhui.springboot.wms.dto.MasterViews;
import com.yinhui.springboot.wms.dto.PageResult;
import com.yinhui.springboot.wms.mapper.WmsQueryMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 实时库存、库存流水、预警和看板。口径与 FastAPI 工具保持一致：
 * 可用量 = 在库量 - 冻结量（数据库生成列）；低库存按“仓库 × 商品”汇总后与安全库存比较。
 */
@Service
public class InventoryQueryService {

    private final WmsQueryMapper queryMapper;

    public InventoryQueryService(WmsQueryMapper queryMapper) {
        this.queryMapper = queryMapper;
    }

    public PageResult<MasterViews.InventoryRow> inventory(Long warehouseId, Long locationId, Long productId,
            String batchNo, String keyword, boolean onlyAvailable, long page, long size) {
        return PageResult.of(queryMapper.selectInventory(new Page<>(page, size, true), warehouseId, locationId,
                productId, trim(batchNo), trim(keyword), onlyAvailable));
    }

    public MasterViews.InventoryRow inventoryItem(long id) {
        MasterViews.InventoryRow row = queryMapper.selectInventoryItem(id);
        if (row == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "库存记录不存在");
        }
        return row;
    }

    public PageResult<MasterViews.InventoryLogRow> logs(Long warehouseId, Long productId, String bizType,
            String orderNo, LocalDate dateFrom, LocalDate dateTo, long page, long size) {
        return PageResult.of(queryMapper.selectInventoryLogs(new Page<>(page, size, true), warehouseId, productId,
                trim(bizType), trim(orderNo), start(dateFrom), endExclusive(dateTo)));
    }

    public PageResult<MasterViews.LowStockRow> lowStock(Long warehouseId, String keyword, boolean includeMissing,
            long page, long size) {
        return PageResult.of(queryMapper.selectLowStock(new Page<>(page, size, true), warehouseId,
                trim(keyword), includeMissing));
    }

    public PageResult<MasterViews.OverstockRow> overstock(Long warehouseId, String keyword, long page, long size) {
        return PageResult.of(queryMapper.selectOverstock(new Page<>(page, size, true), warehouseId, trim(keyword)));
    }

    public PageResult<MasterViews.ExpiringRow> expiring(Long warehouseId, int days, String scope,
            long page, long size) {
        String normalized = StringUtils.hasText(scope) ? scope.strip().toUpperCase(Locale.ROOT) : "EXPIRING";
        if (!normalized.equals("EXPIRING") && !normalized.equals("EXPIRED") && !normalized.equals("ALL")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "临期范围只能为 EXPIRING、EXPIRED 或 ALL");
        }
        int window = Math.max(1, Math.min(days, 365));
        LocalDate today = OrderNumberService.today();
        LocalDate until = today.plusDays(window);
        PageResult<MasterViews.ExpiringRow> result = PageResult.of(queryMapper.selectExpiring(
                new Page<>(page, size, true), warehouseId, normalized, today, until));
        for (MasterViews.ExpiringRow row : result.records()) {
            row.setDaysLeft(ChronoUnit.DAYS.between(today, row.getExpireDate()));
        }
        return result;
    }

    public MasterViews.DashboardSummary dashboard() {
        return queryMapper.selectDashboardSummary();
    }

    private static LocalDateTime start(LocalDate date) {
        return date == null ? null : date.atStartOfDay();
    }

    private static LocalDateTime endExclusive(LocalDate date) {
        return date == null ? null : date.plusDays(1).atStartOfDay();
    }

    private static String trim(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }
}
