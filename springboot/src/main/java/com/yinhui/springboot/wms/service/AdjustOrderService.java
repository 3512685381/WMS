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
import com.yinhui.springboot.wms.entity.WmsLocation;
import com.yinhui.springboot.wms.entity.WmsProduct;
import com.yinhui.springboot.wms.mapper.WmsAdjustItemMapper;
import com.yinhui.springboot.wms.mapper.WmsAdjustOrderMapper;
import com.yinhui.springboot.wms.mapper.WmsQueryMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 库存调整单。盘盈记正数、盘亏记负数；盘点生成的调整单在审核前再次核对库存版本。
 */
@Service
public class AdjustOrderService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final WmsAdjustOrderMapper orderMapper;
    private final WmsAdjustItemMapper itemMapper;
    private final WmsQueryMapper queryMapper;
    private final WmsValidator validator;
    private final OrderNumberService numberService;
    private final InventoryPostingService postingService;
    private final StocktakeOrderService stocktakeService;

    public AdjustOrderService(WmsAdjustOrderMapper orderMapper, WmsAdjustItemMapper itemMapper,
            WmsQueryMapper queryMapper, WmsValidator validator, OrderNumberService numberService,
            InventoryPostingService postingService, StocktakeOrderService stocktakeService) {
        this.orderMapper = orderMapper;
        this.itemMapper = itemMapper;
        this.queryMapper = queryMapper;
        this.validator = validator;
        this.numberService = numberService;
        this.postingService = postingService;
        this.stocktakeService = stocktakeService;
    }

    public PageResult<OrderViews.AdjustOrderRow> list(Integer status, Long warehouseId, String keyword,
            LocalDate dateFrom, LocalDate dateTo, long page, long size) {
        PageResult<OrderViews.AdjustOrderRow> result = PageResult.of(queryMapper.selectAdjustOrders(
                new Page<>(page, size, true), status, warehouseId, trim(keyword),
                start(dateFrom), endExclusive(dateTo)));
        result.records().forEach(row -> row.setStatusText(OrderStatus.labelOf(row.getStatus())));
        return result;
    }

    public OrderViews.AdjustDetail detail(long id) {
        OrderViews.AdjustOrderRow order = queryMapper.selectAdjustOrder(id);
        if (order == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "调整单不存在");
        }
        List<OrderViews.AdjustItemRow> items = queryMapper.selectAdjustItems(id);
        order.setStatusText(OrderStatus.labelOf(order.getStatus()));
        order.setItemCount(items.size());
        order.setTotalQuantity(items.stream().map(OrderViews.AdjustItemRow::getAdjustQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        return new OrderViews.AdjustDetail(order, items);
    }

    @Transactional
    public OrderViews.CreatedOrder create(OrderRequests.Adjust request, long userId) {
        if (request.stocktakeOrderId() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "盘点差异调整单由盘点完结时自动生成");
        }
        validator.requireActiveWarehouse(request.warehouseId());
        List<WmsAdjustItem> items = buildItems(request, userId);
        String orderNo = numberService.next("TZ", userId);
        WmsAdjustOrder order = new WmsAdjustOrder();
        order.setOrderNo(orderNo);
        order.setWarehouseId(request.warehouseId());
        order.setStatus(OrderStatus.DRAFT.code());
        order.setRemark(trim(request.remark()));
        order.setCreatedBy(userId);
        orderMapper.insert(order);
        saveItems(order.getId(), items);
        return new OrderViews.CreatedOrder(order.getId(), orderNo, order.getStatus());
    }

    @Transactional
    public void update(long id, OrderRequests.Adjust request, long userId) {
        WmsAdjustOrder order = lockOrder(id);
        requireEditable(order, "修改");
        if (order.getStocktakeOrderId() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "盘点生成的调整单不能修改，请驳回后重新盘点");
        }
        validator.requireActiveWarehouse(request.warehouseId());
        List<WmsAdjustItem> items = buildItems(request, userId);
        itemMapper.delete(Wrappers.<WmsAdjustItem>lambdaQuery().eq(WmsAdjustItem::getOrderId, id));
        saveItems(id, items);
        orderMapper.update(null, Wrappers.<WmsAdjustOrder>lambdaUpdate()
                .eq(WmsAdjustOrder::getId, id)
                .set(WmsAdjustOrder::getWarehouseId, request.warehouseId())
                .set(WmsAdjustOrder::getRemark, trim(request.remark()))
                .set(WmsAdjustOrder::getStatus, OrderStatus.DRAFT.code())
                .set(WmsAdjustOrder::getSubmittedAt, null)
                .set(WmsAdjustOrder::getReviewedBy, null)
                .set(WmsAdjustOrder::getReviewedAt, null)
                .set(WmsAdjustOrder::getRejectReason, null));
    }

    @Transactional
    public void delete(long id) {
        WmsAdjustOrder order = lockOrder(id);
        requireEditable(order, "删除");
        if (order.getStocktakeOrderId() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "盘点生成的调整单不能删除");
        }
        itemMapper.delete(Wrappers.<WmsAdjustItem>lambdaQuery().eq(WmsAdjustItem::getOrderId, id));
        orderMapper.deleteById(id);
    }

    @Transactional
    public void submit(long id, long userId) {
        WmsAdjustOrder order = lockOrder(id);
        requireEditable(order, "提交");
        Long itemCount = itemMapper.selectCount(Wrappers.<WmsAdjustItem>lambdaQuery()
                .eq(WmsAdjustItem::getOrderId, id));
        if (itemCount == null || itemCount == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "单据没有明细，不能提交审核");
        }
        orderMapper.update(null, Wrappers.<WmsAdjustOrder>lambdaUpdate()
                .eq(WmsAdjustOrder::getId, id)
                .set(WmsAdjustOrder::getStatus, OrderStatus.SUBMITTED.code())
                .set(WmsAdjustOrder::getSubmittedAt, now())
                .set(WmsAdjustOrder::getReviewedBy, null)
                .set(WmsAdjustOrder::getReviewedAt, null)
                .set(WmsAdjustOrder::getRejectReason, null));
    }

    @Transactional
    public void approve(long id, long userId) {
        WmsAdjustOrder order = lockOrder(id);
        if (OrderStatus.of(order.getStatus()) != OrderStatus.SUBMITTED) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "单据当前为" + OrderStatus.labelOf(order.getStatus()) + "，不能重复审核");
        }
        if (order.getStocktakeOrderId() != null) {
            stocktakeService.lockAndVerifyForAdjust(order.getStocktakeOrderId());
        }
        List<WmsAdjustItem> items = itemMapper.selectList(Wrappers.<WmsAdjustItem>lambdaQuery()
                .eq(WmsAdjustItem::getOrderId, id));
        if (items.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "单据没有明细，不能审核");
        }
        List<InventoryPostingService.AdjustLine> lines = items.stream()
                .map(item -> new InventoryPostingService.AdjustLine(item.getLocationId(), item.getProductId(),
                        item.getBatchNo(),
                        "GAIN".equals(item.getAdjustType()) ? item.getAdjustQty() : item.getAdjustQty().negate()))
                .toList();
        postingService.postAdjust(order.getWarehouseId(), order.getOrderNo(), lines, userId);

        LocalDateTime postedAt = now();
        orderMapper.update(null, Wrappers.<WmsAdjustOrder>lambdaUpdate()
                .eq(WmsAdjustOrder::getId, id)
                .set(WmsAdjustOrder::getStatus, OrderStatus.POSTED.code())
                .set(WmsAdjustOrder::getReviewedBy, userId)
                .set(WmsAdjustOrder::getReviewedAt, postedAt)
                .set(WmsAdjustOrder::getPostedAt, postedAt)
                .set(WmsAdjustOrder::getRejectReason, null));
    }

    @Transactional
    public void reject(long id, String reason, long userId) {
        WmsAdjustOrder order = lockOrder(id);
        if (OrderStatus.of(order.getStatus()) != OrderStatus.SUBMITTED) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "单据当前为" + OrderStatus.labelOf(order.getStatus()) + "，不能驳回");
        }
        orderMapper.update(null, Wrappers.<WmsAdjustOrder>lambdaUpdate()
                .eq(WmsAdjustOrder::getId, id)
                .set(WmsAdjustOrder::getStatus, OrderStatus.REJECTED.code())
                .set(WmsAdjustOrder::getReviewedBy, userId)
                .set(WmsAdjustOrder::getReviewedAt, now())
                .set(WmsAdjustOrder::getRejectReason, reason.strip()));
    }

    private List<WmsAdjustItem> buildItems(OrderRequests.Adjust request, long userId) {
        return request.items().stream().map(source -> {
            WmsLocation location = validator.requireLocation(request.warehouseId(), source.locationId());
            WmsProduct product = validator.requireProduct(source.productId());
            WmsAdjustItem item = new WmsAdjustItem();
            item.setWarehouseId(request.warehouseId());
            item.setProductId(product.getId());
            item.setLocationId(location.getId());
            item.setBatchNo(validator.normalizeBatch(product, source.batchNo()));
            item.setAdjustType(source.adjustType());
            item.setAdjustQty(source.adjustQty());
            item.setReason(trim(source.reason()));
            item.setCreatedBy(userId);
            return item;
        }).toList();
    }

    private void saveItems(long orderId, List<WmsAdjustItem> items) {
        for (WmsAdjustItem item : items) {
            item.setOrderId(orderId);
            itemMapper.insert(item);
        }
    }

    private WmsAdjustOrder lockOrder(long id) {
        WmsAdjustOrder order = orderMapper.selectForUpdate(id);
        if (order == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "调整单不存在");
        }
        return order;
    }

    private static void requireEditable(WmsAdjustOrder order, String action) {
        OrderStatus status = OrderStatus.of(order.getStatus());
        if (!status.editable()) {
            throw new ApiException(HttpStatus.CONFLICT, "单据当前为" + status.label() + "，不能" + action);
        }
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
