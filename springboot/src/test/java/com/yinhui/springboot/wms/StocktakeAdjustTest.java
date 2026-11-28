package com.yinhui.springboot.wms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yinhui.springboot.common.exception.ApiException;
import com.yinhui.springboot.wms.dto.OrderRequests;
import com.yinhui.springboot.wms.dto.OrderStatus;
import com.yinhui.springboot.wms.dto.OrderViews;
import com.yinhui.springboot.wms.mapper.WmsInventoryMapper;
import com.yinhui.springboot.wms.service.AdjustOrderService;
import com.yinhui.springboot.wms.service.StocktakeOrderService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StocktakeAdjustTest {

    @Autowired
    private StocktakeOrderService stocktakeService;
    @Autowired
    private AdjustOrderService adjustService;
    @Autowired
    private WmsInventoryMapper inventoryMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void stocktakeDifferenceGeneratesAdjustOrderAndPostsAfterApproval() {
        OrderViews.CreatedOrder stocktake = stocktakeService.create(
                new OrderRequests.Stocktake(1L, 12L, "盘点测试"), 2L);
        OrderViews.StocktakeDetail detail = stocktakeService.detail(stocktake.id());
        assertThat(detail.items()).isNotEmpty();

        OrderViews.StocktakeItemRow target = detail.items().stream()
                .filter(item -> item.getProductId() == 1L && "B001".equals(item.getBatchNo()))
                .findFirst().orElseThrow();
        BigDecimal bookQty = target.getBookQty();

        List<OrderRequests.StocktakeCountItem> counts = detail.items().stream()
                .map(item -> new OrderRequests.StocktakeCountItem(item.getId(),
                        item.getId().equals(target.getId()) ? bookQty.add(new BigDecimal("3")) : item.getBookQty()))
                .toList();
        stocktakeService.count(stocktake.id(), new OrderRequests.StocktakeCount(counts));

        long adjustOrderId = stocktakeService.complete(stocktake.id(), 1L);
        assertThat(adjustOrderId).isPositive();
        assertThat(stocktakeService.detail(stocktake.id()).order().getStatus())
                .isEqualTo(OrderStatus.POSTED.code());

        OrderViews.AdjustDetail adjust = adjustService.detail(adjustOrderId);
        assertThat(adjust.order().getStatus()).isEqualTo(OrderStatus.SUBMITTED.code());
        assertThat(adjust.order().getStocktakeOrderNo()).isEqualTo(stocktake.orderNo());
        assertThat(adjust.items()).hasSize(1);
        assertThat(adjust.items().getFirst().getAdjustType()).isEqualTo("GAIN");
        assertThat(adjust.items().getFirst().getAdjustQty()).isEqualByComparingTo("3");

        adjustService.approve(adjustOrderId, 1L);
        assertThat(inventoryMapper.selectByDimension(1L, 102L, 1L, "B001").getQuantity())
                .isEqualByComparingTo(bookQty.add(new BigDecimal("3")));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wms_inventory_log WHERE order_no = ? AND biz_type = 'ADJUST'",
                Long.class, adjust.order().getOrderNo())).isEqualTo(1);
    }

    @Test
    void completingStocktakeRejectsWhenInventoryVersionChanged() {
        OrderViews.CreatedOrder stocktake = stocktakeService.create(
                new OrderRequests.Stocktake(1L, null, "版本冲突"), 2L);
        OrderViews.StocktakeDetail detail = stocktakeService.detail(stocktake.id());
        List<OrderRequests.StocktakeCountItem> counts = detail.items().stream()
                .map(item -> new OrderRequests.StocktakeCountItem(item.getId(), item.getBookQty()))
                .toList();
        stocktakeService.count(stocktake.id(), new OrderRequests.StocktakeCount(counts));

        jdbcTemplate.update("UPDATE wms_inventory SET quantity = quantity + 1, version = version + 1 WHERE id = 1");

        assertThatThrownBy(() -> stocktakeService.complete(stocktake.id(), 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("库存已变化");
        assertThat(stocktakeService.detail(stocktake.id()).order().getStatus())
                .isEqualTo(OrderStatus.DRAFT.code());
    }

    @Test
    void adjustApprovalRechecksStocktakeVersion() {
        OrderViews.CreatedOrder stocktake = stocktakeService.create(
                new OrderRequests.Stocktake(1L, 12L, "调整前复核"), 2L);
        OrderViews.StocktakeDetail detail = stocktakeService.detail(stocktake.id());
        OrderViews.StocktakeItemRow target = detail.items().getFirst();
        List<OrderRequests.StocktakeCountItem> counts = detail.items().stream()
                .map(item -> new OrderRequests.StocktakeCountItem(item.getId(),
                        item.getId().equals(target.getId())
                                ? item.getBookQty().add(new BigDecimal("2")) : item.getBookQty()))
                .toList();
        stocktakeService.count(stocktake.id(), new OrderRequests.StocktakeCount(counts));
        long adjustOrderId = stocktakeService.complete(stocktake.id(), 1L);

        jdbcTemplate.update("""
                UPDATE wms_inventory SET quantity = quantity + 1, version = version + 1
                WHERE warehouse_id = 1 AND location_id = ? AND product_id = ? AND batch_no = ?
                """, target.getLocationId(), target.getProductId(), target.getBatchNo());

        assertThatThrownBy(() -> adjustService.approve(adjustOrderId, 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("库存已变化");
        assertThat(adjustService.detail(adjustOrderId).order().getStatus())
                .isEqualTo(OrderStatus.SUBMITTED.code());
    }
}
