package com.yinhui.springboot.wms.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yinhui.springboot.common.exception.ApiException;
import com.yinhui.springboot.wms.dto.OrderRequests;
import com.yinhui.springboot.wms.dto.OrderStatus;
import com.yinhui.springboot.wms.dto.OrderViews;
import com.yinhui.springboot.wms.dto.PageResult;
import com.yinhui.springboot.wms.entity.WmsInboundItem;
import com.yinhui.springboot.wms.entity.WmsInboundOrder;
import com.yinhui.springboot.wms.entity.WmsLocation;
import com.yinhui.springboot.wms.entity.WmsProduct;
import com.yinhui.springboot.wms.mapper.WmsInboundItemMapper;
import com.yinhui.springboot.wms.mapper.WmsInboundOrderMapper;
import com.yinhui.springboot.wms.mapper.WmsQueryMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 入库单：草稿 → 待审核 → 已生效 / 已驳回。
 * 审核在单据行锁内完成库存记账，重复或并发审核只会记一次账。
 */
@Service
public class InboundOrderService {

    private static final BigDecimal ZERO_AMOUNT = new BigDecimal("0.0000");

    private final WmsInboundOrderMapper orderMapper;
    private final WmsInboundItemMapper itemMapper;
    private final WmsQueryMapper queryMapper;
    private final WmsValidator validator;
    private final OrderNumberService numberService;
    private final InventoryPostingService postingService;

    public InboundOrderService(WmsInboundOrderMapper orderMapper, WmsInboundItemMapper itemMapper,
            WmsQueryMapper queryMapper, WmsValidator validator, OrderNumberService numberService,
            InventoryPostingService postingService) {
        this.orderMapper = orderMapper;
        this.itemMapper = itemMapper;
        this.queryMapper = queryMapper;
        this.validator = validator;
        this.numberService = numberService;
        this.postingService = postingService;
    }

    public PageResult<OrderViews.InboundOrderRow> list(Integer status, Long warehouseId, String source,
            String keyword, LocalDate dateFrom, LocalDate dateTo, long page, long size) {
        PageResult<OrderViews.InboundOrderRow> result = PageResult.of(queryMapper.selectInboundOrders(
                new Page<>(page, size, true), status, warehouseId, trim(source), trim(keyword),
                start(dateFrom), endExclusive(dateTo)));
        result.records().forEach(row -> row.setStatusText(OrderStatus.labelOf(row.getStatus())));
        return result;
    }

    public OrderViews.InboundDetail detail(long id) {
        OrderViews.InboundOrderRow order = queryMapper.selectInboundOrder(id);
        if (order == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "入库单不存在");
        }
        List<OrderViews.InboundItemRow> items = queryMapper.selectInboundItems(id);
        order.setStatusText(OrderStatus.labelOf(order.getStatus()));
        order.setItemCount(items.size());
        order.setTotalQuantity(items.stream().map(OrderViews.InboundItemRow::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        order.setTotalAmount(items.stream().map(OrderViews.InboundItemRow::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        return new OrderViews.InboundDetail(order, items);
    }

    @Transactional
    public OrderViews.CreatedOrder create(OrderRequests.Inbound request, long userId) {
        validator.requireActiveWarehouse(request.warehouseId());
        validator.requireSupplier(request.supplierId());
        List<WmsInboundItem> items = buildItems(request, userId);
        String orderNo = numberService.next("RK", userId);

        WmsInboundOrder order = new WmsInboundOrder();
        order.setOrderNo(orderNo);
        order.setWarehouseId(request.warehouseId());
        order.setSupplierId(request.supplierId());
        order.setSource("MANUAL");
        order.setStatus(OrderStatus.DRAFT.code());
        order.setRemark(trim(request.remark()));
        order.setCreatedBy(userId);
        orderMapper.insert(order);
        saveItems(order.getId(), items);
        return new OrderViews.CreatedOrder(order.getId(), orderNo, order.getStatus());
    }

    @Transactional
    public void update(long id, OrderRequests.Inbound request, long userId) {
        WmsInboundOrder order = lockOrder(id);
        requireEditable(order, "修改");
        validator.requireActiveWarehouse(request.warehouseId());
        validator.requireSupplier(request.supplierId());
        List<WmsInboundItem> items = buildItems(request, userId);

        itemMapper.delete(Wrappers.<WmsInboundItem>lambdaQuery().eq(WmsInboundItem::getOrderId, id));
        saveItems(id, items);
        orderMapper.update(null, Wrappers.<WmsInboundOrder>lambdaUpdate()
                .eq(WmsInboundOrder::getId, id)
                .set(WmsInboundOrder::getWarehouseId, request.warehouseId())
                .set(WmsInboundOrder::getSupplierId, request.supplierId())
                .set(WmsInboundOrder::getRemark, trim(request.remark()))
                .set(WmsInboundOrder::getStatus, OrderStatus.DRAFT.code())
                .set(WmsInboundOrder::getSubmittedAt, null)
                .set(WmsInboundOrder::getReviewedBy, null)
                .set(WmsInboundOrder::getReviewedAt, null)
                .set(WmsInboundOrder::getRejectReason, null));
    }

    @Transactional
    public void delete(long id) {
        WmsInboundOrder order = lockOrder(id);
        requireEditable(order, "删除");
        itemMapper.delete(Wrappers.<WmsInboundItem>lambdaQuery().eq(WmsInboundItem::getOrderId, id));
        orderMapper.deleteById(id);
    }

    @Transactional
    public void submit(long id, long userId) {
        WmsInboundOrder order = lockOrder(id);
        requireEditable(order, "提交");
        Long itemCount = itemMapper.selectCount(Wrappers.<WmsInboundItem>lambdaQuery()
                .eq(WmsInboundItem::getOrderId, id));
        if (itemCount == null || itemCount == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "单据没有明细，不能提交审核");
        }
        orderMapper.update(null, Wrappers.<WmsInboundOrder>lambdaUpdate()
                .eq(WmsInboundOrder::getId, id)
                .set(WmsInboundOrder::getStatus, OrderStatus.SUBMITTED.code())
                .set(WmsInboundOrder::getSubmittedAt, now())
                .set(WmsInboundOrder::getReviewedBy, null)
                .set(WmsInboundOrder::getReviewedAt, null)
                .set(WmsInboundOrder::getRejectReason, null));
    }

    @Transactional
    public void approve(long id, long userId) {
        WmsInboundOrder order = lockOrder(id);
        if (OrderStatus.of(order.getStatus()) != OrderStatus.SUBMITTED) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "单据当前为" + OrderStatus.labelOf(order.getStatus()) + "，不能重复审核");
        }
        validator.requireActiveWarehouse(order.getWarehouseId());
        List<WmsInboundItem> items = itemMapper.selectList(Wrappers.<WmsInboundItem>lambdaQuery()
                .eq(WmsInboundItem::getOrderId, id));
        if (items.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "单据没有明细，不能审核");
        }
        List<InventoryPostingService.PostingLine> lines = items.stream()
                .map(item -> new InventoryPostingService.PostingLine(item.getLocationId(), item.getProductId(),
                        item.getBatchNo(), item.getQuantity(), item.getProductionDate(), item.getExpireDate()))
                .toList();
        postingService.postInbound(order.getWarehouseId(), order.getOrderNo(), lines, userId);

        LocalDateTime postedAt = now();
        orderMapper.update(null, Wrappers.<WmsInboundOrder>lambdaUpdate()
                .eq(WmsInboundOrder::getId, id)
                .set(WmsInboundOrder::getStatus, OrderStatus.POSTED.code())
                .set(WmsInboundOrder::getReviewedBy, userId)
                .set(WmsInboundOrder::getReviewedAt, postedAt)
                .set(WmsInboundOrder::getPostedAt, postedAt)
                .set(WmsInboundOrder::getRejectReason, null));
    }

    @Transactional
    public void reject(long id, String reason, long userId) {
        WmsInboundOrder order = lockOrder(id);
        if (OrderStatus.of(order.getStatus()) != OrderStatus.SUBMITTED) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "单据当前为" + OrderStatus.labelOf(order.getStatus()) + "，不能驳回");
        }
        orderMapper.update(null, Wrappers.<WmsInboundOrder>lambdaUpdate()
                .eq(WmsInboundOrder::getId, id)
                .set(WmsInboundOrder::getStatus, OrderStatus.REJECTED.code())
                .set(WmsInboundOrder::getReviewedBy, userId)
                .set(WmsInboundOrder::getReviewedAt, now())
                .set(WmsInboundOrder::getRejectReason, reason.strip()));
    }

    private List<WmsInboundItem> buildItems(OrderRequests.Inbound request, long userId) {
        return request.items().stream().map(source -> {
            WmsLocation location = validator.requireLocation(request.warehouseId(), source.locationId());
            WmsProduct product = validator.requireProduct(source.productId());
            WmsValidator.ItemRules rules = validator.inboundRules(product, source.batchNo(),
                    source.productionDate(), source.expireDate());
            WmsInboundItem item = new WmsInboundItem();
            item.setWarehouseId(request.warehouseId());
            item.setProductId(product.getId());
            item.setLocationId(location.getId());
            item.setBatchNo(rules.batchNo());
            item.setProductionDate(rules.productionDate());
            item.setExpireDate(rules.expireDate());
            item.setQuantity(source.quantity());
            item.setUnitPrice(WmsValidator.money(source.unitPrice()));
            item.setCreatedBy(userId);
            return item;
        }).toList();
    }

    private void saveItems(long orderId, List<WmsInboundItem> items) {
        for (WmsInboundItem item : items) {
            item.setOrderId(orderId);
            itemMapper.insert(item);
        }
    }

    private WmsInboundOrder lockOrder(long id) {
        WmsInboundOrder order = orderMapper.selectForUpdate(id);
        if (order == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "入库单不存在");
        }
        return order;
    }

    private static void requireEditable(WmsInboundOrder order, String action) {
        OrderStatus status = OrderStatus.of(order.getStatus());
        if (!status.editable()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "单据当前为" + status.label() + "，不能" + action);
        }
    }

    private static LocalDateTime start(LocalDate date) {
        return date == null ? null : date.atStartOfDay();
    }

    private static LocalDateTime endExclusive(LocalDate date) {
        return date == null ? null : date.plusDays(1).atStartOfDay();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
    }

    private static String trim(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }
}
