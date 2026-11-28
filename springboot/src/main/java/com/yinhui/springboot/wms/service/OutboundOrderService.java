package com.yinhui.springboot.wms.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yinhui.springboot.common.exception.ApiException;
import com.yinhui.springboot.wms.dto.OrderRequests;
import com.yinhui.springboot.wms.dto.OrderStatus;
import com.yinhui.springboot.wms.dto.OrderViews;
import com.yinhui.springboot.wms.dto.PageResult;
import com.yinhui.springboot.wms.entity.WmsLocation;
import com.yinhui.springboot.wms.entity.WmsOutboundItem;
import com.yinhui.springboot.wms.entity.WmsOutboundOrder;
import com.yinhui.springboot.wms.entity.WmsProduct;
import com.yinhui.springboot.wms.mapper.WmsOutboundItemMapper;
import com.yinhui.springboot.wms.mapper.WmsOutboundOrderMapper;
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
 * 出库单。草稿不预留库存，审核时在单据行锁内重新检查可用量并扣减。
 */
@Service
public class OutboundOrderService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final WmsOutboundOrderMapper orderMapper;
    private final WmsOutboundItemMapper itemMapper;
    private final WmsQueryMapper queryMapper;
    private final WmsValidator validator;
    private final OrderNumberService numberService;
    private final InventoryPostingService postingService;

    public OutboundOrderService(WmsOutboundOrderMapper orderMapper, WmsOutboundItemMapper itemMapper,
            WmsQueryMapper queryMapper, WmsValidator validator, OrderNumberService numberService,
            InventoryPostingService postingService) {
        this.orderMapper = orderMapper;
        this.itemMapper = itemMapper;
        this.queryMapper = queryMapper;
        this.validator = validator;
        this.numberService = numberService;
        this.postingService = postingService;
    }

    public PageResult<OrderViews.OutboundOrderRow> list(Integer status, Long warehouseId, String source,
            String keyword, LocalDate dateFrom, LocalDate dateTo, long page, long size) {
        PageResult<OrderViews.OutboundOrderRow> result = PageResult.of(queryMapper.selectOutboundOrders(
                new Page<>(page, size, true), status, warehouseId, trim(source), trim(keyword),
                start(dateFrom), endExclusive(dateTo)));
        result.records().forEach(row -> row.setStatusText(OrderStatus.labelOf(row.getStatus())));
        return result;
    }

    public OrderViews.OutboundDetail detail(long id) {
        OrderViews.OutboundOrderRow order = queryMapper.selectOutboundOrder(id);
        if (order == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "出库单不存在");
        }
        List<OrderViews.OutboundItemRow> items = queryMapper.selectOutboundItems(id);
        order.setStatusText(OrderStatus.labelOf(order.getStatus()));
        order.setItemCount(items.size());
        order.setTotalQuantity(items.stream().map(OrderViews.OutboundItemRow::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        order.setTotalAmount(items.stream().map(OrderViews.OutboundItemRow::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        return new OrderViews.OutboundDetail(order, items);
    }

    @Transactional
    public OrderViews.CreatedOrder create(OrderRequests.Outbound request, long userId) {
        validator.requireActiveWarehouse(request.warehouseId());
        validator.requireCustomer(request.customerId());
        List<WmsOutboundItem> items = buildItems(request, userId);
        String orderNo = numberService.next("CK", userId);

        WmsOutboundOrder order = new WmsOutboundOrder();
        order.setOrderNo(orderNo);
        order.setWarehouseId(request.warehouseId());
        order.setCustomerId(request.customerId());
        order.setSource("MANUAL");
        order.setStatus(OrderStatus.DRAFT.code());
        order.setRemark(trim(request.remark()));
        order.setCreatedBy(userId);
        orderMapper.insert(order);
        saveItems(order.getId(), items);
        return new OrderViews.CreatedOrder(order.getId(), orderNo, order.getStatus());
    }

    @Transactional
    public void update(long id, OrderRequests.Outbound request, long userId) {
        WmsOutboundOrder order = lockOrder(id);
        requireEditable(order, "修改");
        validator.requireActiveWarehouse(request.warehouseId());
        validator.requireCustomer(request.customerId());
        List<WmsOutboundItem> items = buildItems(request, userId);

        itemMapper.delete(Wrappers.<WmsOutboundItem>lambdaQuery().eq(WmsOutboundItem::getOrderId, id));
        saveItems(id, items);
        orderMapper.update(null, Wrappers.<WmsOutboundOrder>lambdaUpdate()
                .eq(WmsOutboundOrder::getId, id)
                .set(WmsOutboundOrder::getWarehouseId, request.warehouseId())
                .set(WmsOutboundOrder::getCustomerId, request.customerId())
                .set(WmsOutboundOrder::getRemark, trim(request.remark()))
                .set(WmsOutboundOrder::getStatus, OrderStatus.DRAFT.code())
                .set(WmsOutboundOrder::getSubmittedAt, null)
                .set(WmsOutboundOrder::getReviewedBy, null)
                .set(WmsOutboundOrder::getReviewedAt, null)
                .set(WmsOutboundOrder::getRejectReason, null));
    }

    @Transactional
    public void delete(long id) {
        WmsOutboundOrder order = lockOrder(id);
        requireEditable(order, "删除");
        itemMapper.delete(Wrappers.<WmsOutboundItem>lambdaQuery().eq(WmsOutboundItem::getOrderId, id));
        orderMapper.deleteById(id);
    }

    @Transactional
    public void submit(long id, long userId) {
        WmsOutboundOrder order = lockOrder(id);
        requireEditable(order, "提交");
        Long itemCount = itemMapper.selectCount(Wrappers.<WmsOutboundItem>lambdaQuery()
                .eq(WmsOutboundItem::getOrderId, id));
        if (itemCount == null || itemCount == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "单据没有明细，不能提交审核");
        }
        orderMapper.update(null, Wrappers.<WmsOutboundOrder>lambdaUpdate()
                .eq(WmsOutboundOrder::getId, id)
                .set(WmsOutboundOrder::getStatus, OrderStatus.SUBMITTED.code())
                .set(WmsOutboundOrder::getSubmittedAt, now())
                .set(WmsOutboundOrder::getReviewedBy, null)
                .set(WmsOutboundOrder::getReviewedAt, null)
                .set(WmsOutboundOrder::getRejectReason, null));
    }

    @Transactional
    public void approve(long id, long userId) {
        WmsOutboundOrder order = lockOrder(id);
        if (OrderStatus.of(order.getStatus()) != OrderStatus.SUBMITTED) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "单据当前为" + OrderStatus.labelOf(order.getStatus()) + "，不能重复审核");
        }
        validator.requireActiveWarehouse(order.getWarehouseId());
        List<WmsOutboundItem> items = itemMapper.selectList(Wrappers.<WmsOutboundItem>lambdaQuery()
                .eq(WmsOutboundItem::getOrderId, id));
        if (items.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "单据没有明细，不能审核");
        }
        List<InventoryPostingService.PostingLine> lines = items.stream()
                .map(item -> new InventoryPostingService.PostingLine(item.getLocationId(), item.getProductId(),
                        item.getBatchNo(), item.getQuantity(), null, null))
                .toList();
        postingService.postOutbound(order.getWarehouseId(), order.getOrderNo(), lines, userId);

        LocalDateTime postedAt = now();
        orderMapper.update(null, Wrappers.<WmsOutboundOrder>lambdaUpdate()
                .eq(WmsOutboundOrder::getId, id)
                .set(WmsOutboundOrder::getStatus, OrderStatus.POSTED.code())
                .set(WmsOutboundOrder::getReviewedBy, userId)
                .set(WmsOutboundOrder::getReviewedAt, postedAt)
                .set(WmsOutboundOrder::getPostedAt, postedAt)
                .set(WmsOutboundOrder::getRejectReason, null));
    }

    @Transactional
    public void reject(long id, String reason, long userId) {
        WmsOutboundOrder order = lockOrder(id);
        if (OrderStatus.of(order.getStatus()) != OrderStatus.SUBMITTED) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "单据当前为" + OrderStatus.labelOf(order.getStatus()) + "，不能驳回");
        }
        orderMapper.update(null, Wrappers.<WmsOutboundOrder>lambdaUpdate()
                .eq(WmsOutboundOrder::getId, id)
                .set(WmsOutboundOrder::getStatus, OrderStatus.REJECTED.code())
                .set(WmsOutboundOrder::getReviewedBy, userId)
                .set(WmsOutboundOrder::getReviewedAt, now())
                .set(WmsOutboundOrder::getRejectReason, reason.strip()));
    }

    private List<WmsOutboundItem> buildItems(OrderRequests.Outbound request, long userId) {
        return request.items().stream().map(source -> {
            WmsLocation location = validator.requireLocation(request.warehouseId(), source.locationId());
            WmsProduct product = validator.requireProduct(source.productId());
            String batchNo = validator.normalizeBatch(product, source.batchNo());
            WmsOutboundItem item = new WmsOutboundItem();
            item.setWarehouseId(request.warehouseId());
            item.setProductId(product.getId());
            item.setLocationId(location.getId());
            item.setBatchNo(batchNo);
            item.setQuantity(source.quantity());
            item.setUnitPrice(WmsValidator.money(source.unitPrice()));
            item.setCreatedBy(userId);
            return item;
        }).toList();
    }

    private void saveItems(long orderId, List<WmsOutboundItem> items) {
        for (WmsOutboundItem item : items) {
            item.setOrderId(orderId);
            itemMapper.insert(item);
        }
    }

    private WmsOutboundOrder lockOrder(long id) {
        WmsOutboundOrder order = orderMapper.selectForUpdate(id);
        if (order == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "出库单不存在");
        }
        return order;
    }

    private static void requireEditable(WmsOutboundOrder order, String action) {
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
