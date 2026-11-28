package com.yinhui.springboot.wms.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yinhui.springboot.common.exception.ApiException;
import com.yinhui.springboot.wms.dto.OrderRequests;
import com.yinhui.springboot.wms.dto.OrderStatus;
import com.yinhui.springboot.wms.dto.OrderViews;
import com.yinhui.springboot.wms.dto.PageResult;
import com.yinhui.springboot.wms.entity.WmsLocation;
import com.yinhui.springboot.wms.entity.WmsProduct;
import com.yinhui.springboot.wms.entity.WmsTransferItem;
import com.yinhui.springboot.wms.entity.WmsTransferOrder;
import com.yinhui.springboot.wms.mapper.WmsQueryMapper;
import com.yinhui.springboot.wms.mapper.WmsTransferItemMapper;
import com.yinhui.springboot.wms.mapper.WmsTransferOrderMapper;
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
 * 移库单。审核时源库存扣减、目标库存增加与两侧流水在同一事务内完成。
 */
@Service
public class TransferOrderService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final WmsTransferOrderMapper orderMapper;
    private final WmsTransferItemMapper itemMapper;
    private final WmsQueryMapper queryMapper;
    private final WmsValidator validator;
    private final OrderNumberService numberService;
    private final InventoryPostingService postingService;

    public TransferOrderService(WmsTransferOrderMapper orderMapper, WmsTransferItemMapper itemMapper,
            WmsQueryMapper queryMapper, WmsValidator validator, OrderNumberService numberService,
            InventoryPostingService postingService) {
        this.orderMapper = orderMapper;
        this.itemMapper = itemMapper;
        this.queryMapper = queryMapper;
        this.validator = validator;
        this.numberService = numberService;
        this.postingService = postingService;
    }

    public PageResult<OrderViews.TransferOrderRow> list(Integer status, Long warehouseId, String keyword,
            LocalDate dateFrom, LocalDate dateTo, long page, long size) {
        PageResult<OrderViews.TransferOrderRow> result = PageResult.of(queryMapper.selectTransferOrders(
                new Page<>(page, size, true), status, warehouseId, trim(keyword),
                start(dateFrom), endExclusive(dateTo)));
        result.records().forEach(row -> row.setStatusText(OrderStatus.labelOf(row.getStatus())));
        return result;
    }

    public OrderViews.TransferDetail detail(long id) {
        OrderViews.TransferOrderRow order = queryMapper.selectTransferOrder(id);
        if (order == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "移库单不存在");
        }
        List<OrderViews.TransferItemRow> items = queryMapper.selectTransferItems(id);
        order.setStatusText(OrderStatus.labelOf(order.getStatus()));
        order.setItemCount(items.size());
        order.setTotalQuantity(items.stream().map(OrderViews.TransferItemRow::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        return new OrderViews.TransferDetail(order, items);
    }

    @Transactional
    public OrderViews.CreatedOrder create(OrderRequests.Transfer request, long userId) {
        validateWarehouses(request.fromWarehouseId(), request.toWarehouseId());
        List<WmsTransferItem> items = buildItems(request, userId);
        String orderNo = numberService.next("YK", userId);
        WmsTransferOrder order = new WmsTransferOrder();
        order.setOrderNo(orderNo);
        order.setFromWarehouseId(request.fromWarehouseId());
        order.setToWarehouseId(request.toWarehouseId());
        order.setStatus(OrderStatus.DRAFT.code());
        order.setRemark(trim(request.remark()));
        order.setCreatedBy(userId);
        orderMapper.insert(order);
        saveItems(order.getId(), items);
        return new OrderViews.CreatedOrder(order.getId(), orderNo, order.getStatus());
    }

    @Transactional
    public void update(long id, OrderRequests.Transfer request, long userId) {
        WmsTransferOrder order = lockOrder(id);
        requireEditable(order, "修改");
        validateWarehouses(request.fromWarehouseId(), request.toWarehouseId());
        List<WmsTransferItem> items = buildItems(request, userId);
        itemMapper.delete(Wrappers.<WmsTransferItem>lambdaQuery().eq(WmsTransferItem::getOrderId, id));
        saveItems(id, items);
        orderMapper.update(null, Wrappers.<WmsTransferOrder>lambdaUpdate()
                .eq(WmsTransferOrder::getId, id)
                .set(WmsTransferOrder::getFromWarehouseId, request.fromWarehouseId())
                .set(WmsTransferOrder::getToWarehouseId, request.toWarehouseId())
                .set(WmsTransferOrder::getRemark, trim(request.remark()))
                .set(WmsTransferOrder::getStatus, OrderStatus.DRAFT.code())
                .set(WmsTransferOrder::getSubmittedAt, null)
                .set(WmsTransferOrder::getReviewedBy, null)
                .set(WmsTransferOrder::getReviewedAt, null)
                .set(WmsTransferOrder::getRejectReason, null));
    }

    @Transactional
    public void delete(long id) {
        WmsTransferOrder order = lockOrder(id);
        requireEditable(order, "删除");
        itemMapper.delete(Wrappers.<WmsTransferItem>lambdaQuery().eq(WmsTransferItem::getOrderId, id));
        orderMapper.deleteById(id);
    }

    @Transactional
    public void submit(long id, long userId) {
        WmsTransferOrder order = lockOrder(id);
        requireEditable(order, "提交");
        Long itemCount = itemMapper.selectCount(Wrappers.<WmsTransferItem>lambdaQuery()
                .eq(WmsTransferItem::getOrderId, id));
        if (itemCount == null || itemCount == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "单据没有明细，不能提交审核");
        }
        orderMapper.update(null, Wrappers.<WmsTransferOrder>lambdaUpdate()
                .eq(WmsTransferOrder::getId, id)
                .set(WmsTransferOrder::getStatus, OrderStatus.SUBMITTED.code())
                .set(WmsTransferOrder::getSubmittedAt, now())
                .set(WmsTransferOrder::getReviewedBy, null)
                .set(WmsTransferOrder::getReviewedAt, null)
                .set(WmsTransferOrder::getRejectReason, null));
    }

    @Transactional
    public void approve(long id, long userId) {
        WmsTransferOrder order = lockOrder(id);
        if (OrderStatus.of(order.getStatus()) != OrderStatus.SUBMITTED) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "单据当前为" + OrderStatus.labelOf(order.getStatus()) + "，不能重复审核");
        }
        validator.requireActiveWarehouse(order.getFromWarehouseId());
        validator.requireActiveWarehouse(order.getToWarehouseId());
        List<WmsTransferItem> items = itemMapper.selectList(Wrappers.<WmsTransferItem>lambdaQuery()
                .eq(WmsTransferItem::getOrderId, id));
        if (items.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "单据没有明细，不能审核");
        }
        List<InventoryPostingService.TransferLine> lines = items.stream()
                .map(item -> new InventoryPostingService.TransferLine(item.getFromLocationId(),
                        item.getToLocationId(), item.getProductId(), item.getBatchNo(), item.getQuantity()))
                .toList();
        postingService.postTransfer(order.getFromWarehouseId(), order.getToWarehouseId(), order.getOrderNo(),
                lines, userId);

        LocalDateTime postedAt = now();
        orderMapper.update(null, Wrappers.<WmsTransferOrder>lambdaUpdate()
                .eq(WmsTransferOrder::getId, id)
                .set(WmsTransferOrder::getStatus, OrderStatus.POSTED.code())
                .set(WmsTransferOrder::getReviewedBy, userId)
                .set(WmsTransferOrder::getReviewedAt, postedAt)
                .set(WmsTransferOrder::getPostedAt, postedAt)
                .set(WmsTransferOrder::getRejectReason, null));
    }

    @Transactional
    public void reject(long id, String reason, long userId) {
        WmsTransferOrder order = lockOrder(id);
        if (OrderStatus.of(order.getStatus()) != OrderStatus.SUBMITTED) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "单据当前为" + OrderStatus.labelOf(order.getStatus()) + "，不能驳回");
        }
        orderMapper.update(null, Wrappers.<WmsTransferOrder>lambdaUpdate()
                .eq(WmsTransferOrder::getId, id)
                .set(WmsTransferOrder::getStatus, OrderStatus.REJECTED.code())
                .set(WmsTransferOrder::getReviewedBy, userId)
                .set(WmsTransferOrder::getReviewedAt, now())
                .set(WmsTransferOrder::getRejectReason, reason.strip()));
    }

    /**
     * 支持库内移库（同仓库、不同库位）与跨仓移库；只要两个仓库都启用即可，
     * 库位是否重合由明细校验按 fromLocationId / toLocationId 逐条判断。
     */
    private void validateWarehouses(long fromWarehouseId, long toWarehouseId) {
        validator.requireActiveWarehouse(fromWarehouseId);
        validator.requireActiveWarehouse(toWarehouseId);
    }

    private List<WmsTransferItem> buildItems(OrderRequests.Transfer request, long userId) {
        return request.items().stream().map(source -> {
            if (source.fromLocationId().equals(source.toLocationId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "源库位与目标库位不能相同");
            }
            WmsLocation from = validator.requireLocation(request.fromWarehouseId(), source.fromLocationId());
            WmsLocation to = validator.requireLocation(request.toWarehouseId(), source.toLocationId());
            WmsProduct product = validator.requireProduct(source.productId());
            WmsTransferItem item = new WmsTransferItem();
            item.setFromWarehouseId(request.fromWarehouseId());
            item.setToWarehouseId(request.toWarehouseId());
            item.setProductId(product.getId());
            item.setFromLocationId(from.getId());
            item.setToLocationId(to.getId());
            item.setBatchNo(validator.normalizeBatch(product, source.batchNo()));
            item.setQuantity(source.quantity());
            item.setCreatedBy(userId);
            return item;
        }).toList();
    }

    private void saveItems(long orderId, List<WmsTransferItem> items) {
        for (WmsTransferItem item : items) {
            item.setOrderId(orderId);
            itemMapper.insert(item);
        }
    }

    private WmsTransferOrder lockOrder(long id) {
        WmsTransferOrder order = orderMapper.selectForUpdate(id);
        if (order == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "移库单不存在");
        }
        return order;
    }

    private static void requireEditable(WmsTransferOrder order, String action) {
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
