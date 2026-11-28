package com.yinhui.springboot.wms.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yinhui.springboot.common.exception.ApiException;
import com.yinhui.springboot.wms.dto.OrderRequests;
import com.yinhui.springboot.wms.dto.OrderStatus;
import com.yinhui.springboot.wms.dto.OrderViews;
import com.yinhui.springboot.wms.dto.PageResult;
import com.yinhui.springboot.wms.entity.WmsAdjustItem;
import com.yinhui.springboot.wms.entity.WmsAdjustOrder;
import com.yinhui.springboot.wms.entity.WmsArea;
import com.yinhui.springboot.wms.entity.WmsInventory;
import com.yinhui.springboot.wms.entity.WmsLocation;
import com.yinhui.springboot.wms.entity.WmsStocktakeItem;
import com.yinhui.springboot.wms.entity.WmsStocktakeOrder;
import com.yinhui.springboot.wms.mapper.WmsAdjustItemMapper;
import com.yinhui.springboot.wms.mapper.WmsAdjustOrderMapper;
import com.yinhui.springboot.wms.mapper.WmsAreaMapper;
import com.yinhui.springboot.wms.mapper.WmsInventoryMapper;
import com.yinhui.springboot.wms.mapper.WmsLocationMapper;
import com.yinhui.springboot.wms.mapper.WmsQueryMapper;
import com.yinhui.springboot.wms.mapper.WmsStocktakeItemMapper;
import com.yinhui.springboot.wms.mapper.WmsStocktakeOrderMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 盘点单。创建时保存账面数量和库存版本快照，录入实盘后完结；
 * 完结前核对库存版本，完结时把差异生成一张待审核的调整单。
 */
@Service
public class StocktakeOrderService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final WmsStocktakeOrderMapper orderMapper;
    private final WmsStocktakeItemMapper itemMapper;
    private final WmsAdjustOrderMapper adjustOrderMapper;
    private final WmsAdjustItemMapper adjustItemMapper;
    private final WmsInventoryMapper inventoryMapper;
    private final WmsLocationMapper locationMapper;
    private final WmsAreaMapper areaMapper;
    private final WmsQueryMapper queryMapper;
    private final WmsValidator validator;
    private final OrderNumberService numberService;

    public StocktakeOrderService(WmsStocktakeOrderMapper orderMapper, WmsStocktakeItemMapper itemMapper,
            WmsAdjustOrderMapper adjustOrderMapper, WmsAdjustItemMapper adjustItemMapper,
            WmsInventoryMapper inventoryMapper, WmsLocationMapper locationMapper, WmsAreaMapper areaMapper,
            WmsQueryMapper queryMapper, WmsValidator validator, OrderNumberService numberService) {
        this.orderMapper = orderMapper;
        this.itemMapper = itemMapper;
        this.adjustOrderMapper = adjustOrderMapper;
        this.adjustItemMapper = adjustItemMapper;
        this.inventoryMapper = inventoryMapper;
        this.locationMapper = locationMapper;
        this.areaMapper = areaMapper;
        this.queryMapper = queryMapper;
        this.validator = validator;
        this.numberService = numberService;
    }

    public PageResult<OrderViews.StocktakeOrderRow> list(Integer status, Long warehouseId, String keyword,
            LocalDate dateFrom, LocalDate dateTo, long page, long size) {
        PageResult<OrderViews.StocktakeOrderRow> result = PageResult.of(queryMapper.selectStocktakeOrders(
                new Page<>(page, size, true), status, warehouseId, trim(keyword),
                start(dateFrom), endExclusive(dateTo)));
        result.records().forEach(row -> row.setStatusText(OrderStatus.labelOf(row.getStatus())));
        return result;
    }

    public OrderViews.StocktakeDetail detail(long id) {
        OrderViews.StocktakeOrderRow order = queryMapper.selectStocktakeOrder(id);
        if (order == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "盘点单不存在");
        }
        List<OrderViews.StocktakeItemRow> items = queryMapper.selectStocktakeItems(id);
        order.setStatusText(OrderStatus.labelOf(order.getStatus()));
        order.setItemCount(items.size());
        order.setCountedCount((int) items.stream().filter(item -> item.getActualQty() != null).count());
        order.setDiffCount((int) items.stream().filter(StocktakeOrderService::hasDiff).count());
        order.setTotalDiff(items.stream().filter(item -> item.getActualQty() != null)
                .map(item -> item.getActualQty().subtract(item.getBookQty()).abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        return new OrderViews.StocktakeDetail(order, items);
    }

    @Transactional
    public OrderViews.CreatedOrder create(OrderRequests.Stocktake request, long userId) {
        validator.requireActiveWarehouse(request.warehouseId());
        if (request.areaId() != null) {
            WmsArea area = areaMapper.selectById(request.areaId());
            if (area == null || area.getWarehouseId().longValue() != request.warehouseId()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "库区不属于指定仓库");
            }
        }
        List<WmsInventory> snapshot = loadSnapshot(request.warehouseId(), request.areaId());
        if (snapshot.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "该范围内没有可盘点的库存");
        }
        String orderNo = numberService.next("PD", userId);
        WmsStocktakeOrder order = new WmsStocktakeOrder();
        order.setOrderNo(orderNo);
        order.setWarehouseId(request.warehouseId());
        order.setStatus(OrderStatus.DRAFT.code());
        order.setRemark(trim(request.remark()));
        order.setCreatedBy(userId);
        orderMapper.insert(order);

        for (WmsInventory inventory : snapshot) {
            WmsStocktakeItem item = new WmsStocktakeItem();
            item.setOrderId(order.getId());
            item.setWarehouseId(request.warehouseId());
            item.setProductId(inventory.getProductId());
            item.setLocationId(inventory.getLocationId());
            item.setBatchNo(inventory.getBatchNo());
            item.setBookQty(inventory.getQuantity());
            item.setInventoryVersion(inventory.getVersion());
            item.setCreatedBy(userId);
            itemMapper.insert(item);
        }
        return new OrderViews.CreatedOrder(order.getId(), orderNo, order.getStatus());
    }

    @Transactional
    public void count(long id, OrderRequests.StocktakeCount request) {
        WmsStocktakeOrder order = lockOrder(id);
        if (OrderStatus.of(order.getStatus()) != OrderStatus.DRAFT) {
            throw new ApiException(HttpStatus.CONFLICT, "只有草稿盘点单可以录入实盘数量");
        }
        List<String> missing = new ArrayList<>();
        for (OrderRequests.StocktakeCountItem line : request.items()) {
            WmsStocktakeItem item = itemMapper.selectOne(Wrappers.<WmsStocktakeItem>lambdaQuery()
                    .eq(WmsStocktakeItem::getId, line.itemId())
                    .eq(WmsStocktakeItem::getOrderId, id));
            if (item == null) {
                missing.add(String.valueOf(line.itemId()));
                continue;
            }
            itemMapper.update(null, Wrappers.<WmsStocktakeItem>lambdaUpdate()
                    .eq(WmsStocktakeItem::getId, item.getId())
                    .set(WmsStocktakeItem::getActualQty, line.actualQty()));
        }
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "盘点明细不存在或不属于该单据：" + String.join("、", missing));
        }
    }

    @Transactional
    public long complete(long id, long userId) {
        WmsStocktakeOrder order = lockOrder(id);
        if (OrderStatus.of(order.getStatus()) != OrderStatus.DRAFT) {
            throw new ApiException(HttpStatus.CONFLICT, "只有草稿盘点单可以完结");
        }
        List<WmsStocktakeItem> items = itemMapper.selectList(Wrappers.<WmsStocktakeItem>lambdaQuery()
                .eq(WmsStocktakeItem::getOrderId, id));
        if (items.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "盘点单没有明细");
        }
        List<String> uncounted = items.stream().filter(item -> item.getActualQty() == null)
                .map(item -> "库位 " + item.getLocationId() + " / 商品 " + item.getProductId())
                .toList();
        if (!uncounted.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "仍有明细未录入实盘数量：" + String.join("；", uncounted));
        }
        verifyVersions(items, false);

        List<WmsStocktakeItem> differences = items.stream().filter(StocktakeOrderService::hasDiff).toList();
        Long adjustOrderId = null;
        if (!differences.isEmpty()) {
            adjustOrderId = createAdjustOrder(order, differences, userId);
        }

        LocalDateTime postedAt = now();
        orderMapper.update(null, Wrappers.<WmsStocktakeOrder>lambdaUpdate()
                .eq(WmsStocktakeOrder::getId, id)
                .set(WmsStocktakeOrder::getStatus, OrderStatus.POSTED.code())
                .set(WmsStocktakeOrder::getReviewedBy, userId)
                .set(WmsStocktakeOrder::getReviewedAt, postedAt)
                .set(WmsStocktakeOrder::getPostedAt, postedAt)
                .set(WmsStocktakeOrder::getRejectReason, null));
        return adjustOrderId == null ? 0L : adjustOrderId;
    }

    /**
     * 调整单审核前再次核对库存版本，避免覆盖盘点期间的正常出入库；lockRows=true 时按固定顺序锁行。
     */
    public void lockAndVerifyForAdjust(long stocktakeOrderId) {
        List<WmsStocktakeItem> items = itemMapper.selectList(Wrappers.<WmsStocktakeItem>lambdaQuery()
                .eq(WmsStocktakeItem::getOrderId, stocktakeOrderId));
        List<WmsStocktakeItem> differences = items.stream()
                .filter(item -> item.getActualQty() != null && hasDiff(item))
                .sorted((left, right) -> {
                    int result = Long.compare(left.getLocationId(), right.getLocationId());
                    if (result != 0) {
                        return result;
                    }
                    result = Long.compare(left.getProductId(), right.getProductId());
                    return result != 0 ? result : left.getBatchNo().compareTo(right.getBatchNo());
                })
                .toList();
        List<String> changed = new ArrayList<>();
        for (WmsStocktakeItem item : differences) {
            WmsInventory inventory = inventoryMapper.selectForUpdate(item.getWarehouseId(), item.getLocationId(),
                    item.getProductId(), item.getBatchNo());
            Long currentVersion = inventory == null ? null : inventory.getVersion();
            if (!Objects.equals(currentVersion, item.getInventoryVersion())) {
                changed.add(label(item));
            }
        }
        if (!changed.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "盘点后库存已变化，请重新盘点后再审核调整单：" + String.join("；", changed));
        }
    }

    private Long createAdjustOrder(WmsStocktakeOrder stocktake, List<WmsStocktakeItem> differences, long userId) {
        String orderNo = numberService.next("TZ", userId);
        WmsAdjustOrder adjust = new WmsAdjustOrder();
        adjust.setOrderNo(orderNo);
        adjust.setWarehouseId(stocktake.getWarehouseId());
        adjust.setStocktakeOrderId(stocktake.getId());
        adjust.setStatus(OrderStatus.SUBMITTED.code());
        adjust.setRemark("由盘点单 " + stocktake.getOrderNo() + " 生成");
        adjust.setSubmittedAt(now());
        adjust.setCreatedBy(userId);
        adjustOrderMapper.insert(adjust);

        for (WmsStocktakeItem item : differences) {
            BigDecimal diff = item.getActualQty().subtract(item.getBookQty());
            WmsAdjustItem adjustItem = new WmsAdjustItem();
            adjustItem.setOrderId(adjust.getId());
            adjustItem.setWarehouseId(stocktake.getWarehouseId());
            adjustItem.setProductId(item.getProductId());
            adjustItem.setLocationId(item.getLocationId());
            adjustItem.setBatchNo(item.getBatchNo());
            adjustItem.setAdjustType(diff.signum() > 0 ? "GAIN" : "LOSS");
            adjustItem.setAdjustQty(diff.abs());
            adjustItem.setReason("盘点差异：" + stocktake.getOrderNo());
            adjustItem.setCreatedBy(userId);
            adjustItemMapper.insert(adjustItem);
        }
        return adjust.getId();
    }

    private void verifyVersions(List<WmsStocktakeItem> items, boolean lockRows) {
        List<String> changed = new ArrayList<>();
        for (WmsStocktakeItem item : items) {
            WmsInventory inventory = lockRows
                    ? inventoryMapper.selectForUpdate(item.getWarehouseId(), item.getLocationId(),
                            item.getProductId(), item.getBatchNo())
                    : inventoryMapper.selectByDimension(item.getWarehouseId(), item.getLocationId(),
                            item.getProductId(), item.getBatchNo());
            Long currentVersion = inventory == null ? null : inventory.getVersion();
            if (!Objects.equals(currentVersion, item.getInventoryVersion())) {
                changed.add(label(item));
            }
        }
        if (!changed.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "盘点期间库存已变化，请核对后重新盘点：" + String.join("；", changed));
        }
    }

    private List<WmsInventory> loadSnapshot(long warehouseId, Long areaId) {
        var query = Wrappers.<WmsInventory>lambdaQuery()
                .eq(WmsInventory::getWarehouseId, warehouseId)
                .gt(WmsInventory::getQuantity, BigDecimal.ZERO)
                .orderByAsc(WmsInventory::getLocationId)
                .orderByAsc(WmsInventory::getProductId)
                .orderByAsc(WmsInventory::getBatchNo);
        if (areaId != null) {
            List<Long> locationIds = locationMapper.selectList(Wrappers.<WmsLocation>lambdaQuery()
                    .eq(WmsLocation::getWarehouseId, warehouseId)
                    .eq(WmsLocation::getAreaId, areaId)).stream().map(WmsLocation::getId).toList();
            if (locationIds.isEmpty()) {
                return List.of();
            }
            query.in(WmsInventory::getLocationId, locationIds);
        }
        return inventoryMapper.selectList(query);
    }

    private WmsStocktakeOrder lockOrder(long id) {
        WmsStocktakeOrder order = orderMapper.selectForUpdate(id);
        if (order == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "盘点单不存在");
        }
        return order;
    }

    private static boolean hasDiff(OrderViews.StocktakeItemRow item) {
        return item.getActualQty() != null && item.getActualQty().compareTo(item.getBookQty()) != 0;
    }

    private static boolean hasDiff(WmsStocktakeItem item) {
        return item.getActualQty() != null && item.getActualQty().compareTo(item.getBookQty()) != 0;
    }

    private static String label(WmsStocktakeItem item) {
        return "库位 " + item.getLocationId() + " / 商品 " + item.getProductId() + " / 批次 " + item.getBatchNo();
    }

    private static LocalDateTime start(LocalDate date) {
        return date == null ? null : date.atStartOfDay();
    }

    private static LocalDateTime endExclusive(LocalDate date) {
        return date == null ? null : date.plusDays(1).atStartOfDay();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(BUSINESS_ZONE);
    }

    private static String trim(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }
}
