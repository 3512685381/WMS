package com.yinhui.springboot.wms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yinhui.springboot.common.exception.ApiException;
import com.yinhui.springboot.wms.dto.OrderRequests;
import com.yinhui.springboot.wms.dto.OrderStatus;
import com.yinhui.springboot.wms.dto.OrderViews;
import com.yinhui.springboot.wms.mapper.WmsInventoryMapper;
import com.yinhui.springboot.wms.service.InboundOrderService;
import com.yinhui.springboot.wms.service.OutboundOrderService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class InboundOutboundFlowTest {

    private static final long WAREHOUSE = 1L;
    private static final long LOCATION = 102L;
    private static final long PRODUCT = 1L;
    private static final long SUPPLIER = 1L;
    private static final long CUSTOMER = 1L;

    @Autowired
    private InboundOrderService inboundService;
    @Autowired
    private OutboundOrderService outboundService;
    @Autowired
    private WmsInventoryMapper inventoryMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void inboundDraftIsPostedExactlyOnce() {
        String batch = batch("IN");
        OrderViews.CreatedOrder created = inboundService.create(inbound(batch, "7"), 2L);
        assertThat(created.status()).isEqualTo(OrderStatus.DRAFT.code());

        inboundService.submit(created.id(), 2L);
        assertThat(inboundService.detail(created.id()).order().getStatus())
                .isEqualTo(OrderStatus.SUBMITTED.code());

        inboundService.approve(created.id(), 1L);
        OrderViews.InboundDetail detail = inboundService.detail(created.id());
        assertThat(detail.order().getStatus()).isEqualTo(OrderStatus.POSTED.code());
        assertThat(detail.order().getReviewerName()).isEqualTo("仓储管理员");
        assertThat(detail.items()).hasSize(1);

        var inventory = inventoryMapper.selectByDimension(WAREHOUSE, LOCATION, PRODUCT, batch);
        assertThat(inventory.getQuantity()).isEqualByComparingTo("7.000");
        assertThat(logCount(created.orderNo(), "INBOUND")).isEqualTo(1);

        assertThatThrownBy(() -> inboundService.approve(created.id(), 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不能重复审核");
        assertThat(inventoryMapper.selectByDimension(WAREHOUSE, LOCATION, PRODUCT, batch).getQuantity())
                .isEqualByComparingTo("7.000");
    }

    @Test
    void rejectedDraftCanBeEditedAndResubmitted() {
        String batch = batch("RJ");
        OrderViews.CreatedOrder created = inboundService.create(inbound(batch, "8"), 2L);
        inboundService.submit(created.id(), 2L);
        inboundService.reject(created.id(), "数量需要复核", 1L);

        OrderViews.InboundDetail rejected = inboundService.detail(created.id());
        assertThat(rejected.order().getStatus()).isEqualTo(OrderStatus.REJECTED.code());
        assertThat(rejected.order().getRejectReason()).isEqualTo("数量需要复核");

        inboundService.update(created.id(), inbound(batch, "9"), 2L);
        assertThat(inboundService.detail(created.id()).order().getStatus())
                .isEqualTo(OrderStatus.DRAFT.code());

        inboundService.submit(created.id(), 2L);
        inboundService.approve(created.id(), 1L);
        assertThat(inventoryMapper.selectByDimension(WAREHOUSE, LOCATION, PRODUCT, batch).getQuantity())
                .isEqualByComparingTo("9.000");
    }

    @Test
    void draftCanBeDeletedOnlyBeforeSubmission() {
        String batch = batch("DL");
        OrderViews.CreatedOrder created = inboundService.create(inbound(batch, "1"), 2L);
        inboundService.delete(created.id());
        assertThatThrownBy(() -> inboundService.detail(created.id()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不存在");

        OrderViews.CreatedOrder submitted = inboundService.create(inbound(batch("DL2"), "2"), 2L);
        inboundService.submit(submitted.id(), 2L);
        assertThatThrownBy(() -> inboundService.delete(submitted.id()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不能删除");
    }

    @Test
    void outboundApprovalDeductsStockAndKeepsFrozenQuantity() {
        String batch = "B001";
        var before = inventoryMapper.selectByDimension(WAREHOUSE, LOCATION, PRODUCT, batch);
        BigDecimal frozen = before.getFrozenQuantity();
        OrderViews.CreatedOrder created = outboundService.create(outbound(batch, "5"), 2L);
        outboundService.submit(created.id(), 2L);
        outboundService.approve(created.id(), 1L);

        var after = inventoryMapper.selectByDimension(WAREHOUSE, LOCATION, PRODUCT, batch);
        assertThat(after.getQuantity()).isEqualByComparingTo(before.getQuantity().subtract(new BigDecimal("5")));
        assertThat(after.getFrozenQuantity()).isEqualByComparingTo(frozen);
        assertThat(logCount(created.orderNo(), "OUTBOUND")).isEqualTo(1);
    }

    @Test
    void outboundCannotUseFrozenStock() {
        var before = inventoryMapper.selectByDimension(WAREHOUSE, LOCATION, PRODUCT, "B001");
        BigDecimal available = before.getQuantity().subtract(before.getFrozenQuantity());
        String quantity = available.add(new BigDecimal("0.001")).toPlainString();

        OrderViews.CreatedOrder created = outboundService.create(outbound("B001", quantity), 2L);
        outboundService.submit(created.id(), 2L);
        assertThatThrownBy(() -> outboundService.approve(created.id(), 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("可用库存不足");

        var after = inventoryMapper.selectByDimension(WAREHOUSE, LOCATION, PRODUCT, "B001");
        assertThat(after.getQuantity()).isEqualByComparingTo(before.getQuantity());
        assertThat(outboundService.detail(created.id()).order().getStatus())
                .isEqualTo(OrderStatus.SUBMITTED.code());
    }

    @Test
    void batchRulesAreEnforced() {
        OrderRequests.Inbound request = new OrderRequests.Inbound(WAREHOUSE, SUPPLIER, "批次规则",
                List.of(new OrderRequests.InboundItem(3L, 105L, "WRONG", null, null,
                        new BigDecimal("1"), new BigDecimal("1"))));
        assertThatThrownBy(() -> inboundService.create(request, 2L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不按批次管理");
    }

    @Test
    void aiCreatedDraftGoesThroughTheSameApprovalFlow() {
        String orderNo = "RK209901010001";
        String batch = batch("AI");
        jdbcTemplate.update("""
                INSERT INTO wms_inbound_order (order_no, warehouse_id, supplier_id, source, request_key, status, created_by)
                VALUES (?, ?, ?, 'AI_AGENT', ?, 0, 2)
                """, orderNo, WAREHOUSE, SUPPLIER, "ai:test:" + UUID.randomUUID());
        Long orderId = jdbcTemplate.queryForObject(
                "SELECT id FROM wms_inbound_order WHERE order_no = ?", Long.class, orderNo);
        jdbcTemplate.update("""
                INSERT INTO wms_inbound_item
                    (order_id, warehouse_id, product_id, location_id, batch_no, quantity, unit_price, created_by)
                VALUES (?, ?, ?, ?, ?, 6.000, 12.5000, 2)
                """, orderId, WAREHOUSE, PRODUCT, LOCATION, batch);

        inboundService.submit(orderId, 2L);
        inboundService.approve(orderId, 1L);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT source FROM wms_inbound_order WHERE id = ?", String.class, orderId))
                .isEqualTo("AI_AGENT");
        assertThat(inventoryMapper.selectByDimension(WAREHOUSE, LOCATION, PRODUCT, batch).getQuantity())
                .isEqualByComparingTo("6.000");
    }

    private OrderRequests.Inbound inbound(String batch, String quantity) {
        return new OrderRequests.Inbound(WAREHOUSE, SUPPLIER, "测试入库",
                List.of(new OrderRequests.InboundItem(PRODUCT, LOCATION, batch, null, null,
                        new BigDecimal(quantity), new BigDecimal("12.5000"))));
    }

    private OrderRequests.Outbound outbound(String batch, String quantity) {
        return new OrderRequests.Outbound(WAREHOUSE, CUSTOMER, "测试出库",
                List.of(new OrderRequests.OutboundItem(PRODUCT, LOCATION, batch,
                        new BigDecimal(quantity), new BigDecimal("29.9000"))));
    }

    private long logCount(String orderNo, String bizType) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wms_inventory_log WHERE order_no = ? AND biz_type = ?",
                Long.class, orderNo, bizType);
        return count == null ? 0 : count;
    }

    private static String batch(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
