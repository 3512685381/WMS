package com.yinhui.springboot.wms.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yinhui.springboot.common.exception.ApiException;
import com.yinhui.springboot.wms.dto.OrderRequests;
import com.yinhui.springboot.wms.dto.OrderStatus;
import com.yinhui.springboot.wms.dto.OrderViews;
import com.yinhui.springboot.wms.dto.PageResult;
import com.yinhui.springboot.wms.entity.WmsFreezeItem;
import com.yinhui.springboot.wms.entity.WmsFreezeOrder;
import com.yinhui.springboot.wms.entity.WmsLocation;
import com.yinhui.springboot.wms.entity.WmsProduct;
import com.yinhui.springboot.wms.mapper.WmsFreezeItemMapper;
import com.yinhui.springboot.wms.mapper.WmsFreezeOrderMapper;
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
 * 冻结与解冻单。审核时只变动冻结量，不改变在库量；冻结后可用量下降，出库审核会自然失败。
 */
@Service
public class FreezeOrderService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final WmsFreezeOrderMapper orderMapper;
    private final WmsFreezeItemMapper itemMapper;
    private final WmsQueryMapper queryMapper;
    private final WmsValidator validator;
    private final OrderNumberService numberService;
    private final InventoryPostingService postingService;

    public FreezeOrderService(WmsFreezeOrderMapper orderMapper, WmsFreezeItemMapper itemMapper,
            WmsQueryMapper queryMapper, WmsValidator validator, OrderNumberService numberService,
            InventoryPostingService postingService) {
        this.orderMapper = orderMapper;
        this.itemMapper = itemMapper;
        this.queryMapper = queryMapper;
        this.validator = validator;
        this.numberService = numberService;
        this.postingService = postingService;
    }

    public PageResult<OrderViews.FreezeOrderRow> list(Integer status, Long warehouseId, String keyword,
            LocalDate dateFrom, LocalDate dateTo, long page, long size) {
        PageResult<OrderViews.FreezeOrderRow> result = PageResult.of(queryMapper.selectFreezeOrders(
                new Page<>(page, size, true), status, warehouseId, trim(keyword),
                start(dateFrom), endExclusive(dateTo)));
        result.records().forEach(row -> row.setStatusText(OrderStatus.labelOf(row.getStatus())));
        return result;
    }

    public OrderViews.FreezeDetail detail(long id) {
        OrderViews.FreezeOrderRow order = queryMapper.selectFreezeOrder(id);
        if (order == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "冻结单不存在");
        }
        List<OrderViews.FreezeItemRow> items = queryMapper.selectFreezeItems(id);
        order.setStatusText(OrderStatus.labelOf(order.getStatus()));
        order.setItemCount(items.size());
        order.setTotalQuantity(items.stream().map(OrderViews.FreezeItemRow::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        return new OrderViews.FreezeDetail(order, items);
    }

    @Transactional
    public OrderViews.CreatedOrder create(OrderRequests.Freeze request, long userId) {
        validator.requireActiveWarehouse(request.warehouseId());
        List<WmsFreezeItem> items = buildItems(request, userId);
        String orderNo = numberService.next("DJ", userId);
        WmsFreezeOrder order = new WmsFreezeOrder();
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
    public void update(long id, OrderRequests.Freeze request, long userId) {
        WmsFreezeOrder order = lockOrder(id);
        requireEditable(order, "修改");
        validator.requireActiveWarehouse(request.warehouseId());
        List<WmsFreezeItem> items = buildItems(request, userId);
        itemMapper.delete(Wrappers.<WmsFreezeItem>lambdaQuery().eq(WmsFreezeItem::getOrderId, id));
        saveItems(id, items);
        orderMapper.update(null, Wrappers.<WmsFreezeOrder>lambdaUpdate()
                .eq(WmsFreezeOrder::getId, id)
                .set(WmsFreezeOrder::getWarehouseId, request.warehouseId())
                .set(WmsFreezeOrder::getRemark, trim(request.remark()))
                .set(WmsFreezeOrder::getStatus, OrderStatus.DRAFT.code())
                .set(WmsFreezeOrder::getSubmittedAt, null)
                .set(WmsFreezeOrder::getReviewedBy, null)
                .set(WmsFreezeOrder::getReviewedAt, null)
                .set(WmsFreezeOrder::getRejectReason, null));
    }

    @Transactional
    public void delete(long id) {
        WmsFreezeOrder order = lockOrder(id);
        requireEditable(order, "删除");
        itemMapper.delete(Wrappers.<WmsFreezeItem>lambdaQuery().eq(WmsFreezeItem::getOrderId, id));
        orderMapper.deleteById(id);
    }

    @Transactional
    public void submit(long id, long userId) {
        WmsFreezeOrder order = lockOrder(id);
        requireEditable(order, "提交");
        Long itemCount = itemMapper.selectCount(Wrappers.<WmsFreezeItem>lambdaQuery()
                .eq(WmsFreezeItem::getOrderId, id));
        if (itemCount == null || itemCount == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "单据没有明细，不能提交审核");
        }
        orderMapper.update(null, Wrappers.<WmsFreezeOrder>lambdaUpdate()
                .eq(WmsFreezeOrder::getId, id)
                .set(WmsFreezeOrder::getStatus, OrderStatus.SUBMITTED.code())
                .set(WmsFreezeOrder::getSubmittedAt, now())
                .set(WmsFreezeOrder::getReviewedBy, null)
                .set(WmsFreezeOrder::getReviewedAt, null)
                .set(WmsFreezeOrder::getRejectReason, null));
    }

    @Transactional
    public void approve(long id, long userId) {
        WmsFreezeOrder order = lockOrder(id);
        if (OrderStatus.of(order.getStatus()) != OrderStatus.SUBMITTED) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "单据当前为" + OrderStatus.labelOf(order.getStatus()) + "，不能重复审核");
        }
        List<WmsFreezeItem> items = itemMapper.selectList(Wrappers.<WmsFreezeItem>lambdaQuery()
                .eq(WmsFreezeItem::getOrderId, id));
        if (items.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "单据没有明细，不能审核");
        }
        List<InventoryPostingService.FreezeLine> lines = items.stream()
                .map(item -> new InventoryPostingService.FreezeLine(item.getLocationId(), item.getProductId(),
                        item.getBatchNo(), item.getOpType(), item.getQuantity()))
                .toList();
        postingService.postFreeze(order.getWarehouseId(), order.getOrderNo(), lines, userId);

        LocalDateTime postedAt = now();
        orderMapper.update(null, Wrappers.<WmsFreezeOrder>lambdaUpdate()
                .eq(WmsFreezeOrder::getId, id)
                .set(WmsFreezeOrder::getStatus, OrderStatus.POSTED.code())
                .set(WmsFreezeOrder::getReviewedBy, userId)
                .set(WmsFreezeOrder::getReviewedAt, postedAt)
                .set(WmsFreezeOrder::getPostedAt, postedAt)
                .set(WmsFreezeOrder::getRejectReason, null));
    }

    @Transactional
    public void reject(long id, String reason, long userId) {
        WmsFreezeOrder order = lockOrder(id);
        if (OrderStatus.of(order.getStatus()) != OrderStatus.SUBMITTED) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "单据当前为" + OrderStatus.labelOf(order.getStatus()) + "，不能驳回");
        }
        orderMapper.update(null, Wrappers.<WmsFreezeOrder>lambdaUpdate()
                .eq(WmsFreezeOrder::getId, id)
                .set(WmsFreezeOrder::getStatus, OrderStatus.REJECTED.code())
                .set(WmsFreezeOrder::getReviewedBy, userId)
                .set(WmsFreezeOrder::getReviewedAt, now())
                .set(WmsFreezeOrder::getRejectReason, reason.strip()));
    }

    private List<WmsFreezeItem> buildItems(OrderRequests.Freeze request, long userId) {
        return request.items().stream().map(source -> {
            WmsLocation location = validator.requireLocation(request.warehouseId(), source.locationId());
            WmsProduct product = validator.requireProduct(source.productId());
            WmsFreezeItem item = new WmsFreezeItem();
            item.setWarehouseId(request.warehouseId());
            item.setProductId(product.getId());
            item.setLocationId(location.getId());
            item.setBatchNo(validator.normalizeBatch(product, source.batchNo()));
            item.setOpType(source.opType());
            item.setQuantity(source.quantity());
            item.setReason(trim(source.reason()));
            item.setCreatedBy(userId);
            return item;
        }).toList();
    }

    private void saveItems(long orderId, List<WmsFreezeItem> items) {
        for (WmsFreezeItem item : items) {
            item.setOrderId(orderId);
            itemMapper.insert(item);
        }
    }

    private WmsFreezeOrder lockOrder(long id) {
        WmsFreezeOrder order = orderMapper.selectForUpdate(id);
        if (order == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "冻结单不存在");
        }
        return order;
    }

    private static void requireEditable(WmsFreezeOrder order, String action) {
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
