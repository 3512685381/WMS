package com.yinhui.springboot.wms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yinhui.springboot.common.exception.ApiException;
import com.yinhui.springboot.wms.dto.OrderRequests;
import com.yinhui.springboot.wms.dto.OrderStatus;
import com.yinhui.springboot.wms.dto.OrderViews;
import com.yinhui.springboot.wms.mapper.WmsInventoryMapper;
import com.yinhui.springboot.wms.service.FreezeOrderService;
import com.yinhui.springboot.wms.service.TransferOrderService;
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
class FreezeTransferTest {

    @Autowired
    private FreezeOrderService freezeService;
    @Autowired
    private TransferOrderService transferService;
    @Autowired
    private WmsInventoryMapper inventoryMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void freezeAndUnfreezeChangeOnlyFrozenQuantity() {
        var before = inventoryMapper.selectByDimension(1L, 102L, 1L, "B001");

        OrderViews.CreatedOrder freeze = freezeService.create(freezeRequest("FREEZE", "5"), 2L);
        freezeService.submit(freeze.id(), 2L);
        freezeService.approve(freeze.id(), 1L);

        var frozen = inventoryMapper.selectByDimension(1L, 102L, 1L, "B001");
        assertThat(frozen.getQuantity()).isEqualByComparingTo(before.getQuantity());
        assertThat(frozen.getFrozenQuantity()).isEqualByComparingTo(before.getFrozenQuantity().add(new BigDecimal("5")));

        OrderViews.CreatedOrder unfreeze = freezeService.create(freezeRequest("UNFREEZE", "2"), 2L);
        freezeService.submit(unfreeze.id(), 2L);
        freezeService.approve(unfreeze.id(), 1L);

        var unfrozen = inventoryMapper.selectByDimension(1L, 102L, 1L, "B001");
        assertThat(unfrozen.getFrozenQuantity())
                .isEqualByComparingTo(before.getFrozenQuantity().add(new BigDecimal("3")));
        assertThat(logCount(freeze.orderNo(), "FREEZE")).isEqualTo(1);
        assertThat(logCount(unfreeze.orderNo(), "UNFREEZE")).isEqualTo(1);
    }

    @Test
    void freezeCannotExceedAvailableQuantity() {
        var before = inventoryMapper.selectByDimension(1L, 102L, 1L, "B001");
        BigDecimal available = before.getQuantity().subtract(before.getFrozenQuantity());
        OrderViews.CreatedOrder freeze = freezeService.create(
                freezeRequest("FREEZE", available.add(new BigDecimal("0.001")).toPlainString()), 2L);
        freezeService.submit(freeze.id(), 2L);

        assertThatThrownBy(() -> freezeService.approve(freeze.id(), 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("冻结数量校验失败");
        assertThat(inventoryMapper.selectByDimension(1L, 102L, 1L, "B001").getFrozenQuantity())
                .isEqualByComparingTo(before.getFrozenQuantity());
    }

    @Test
    void transferMovesStockBetweenLocationsInOneTransaction() {
        OrderViews.CreatedOrder transfer = transferService.create(new OrderRequests.Transfer(1L, 1L, "移库测试",
                List.of(new OrderRequests.TransferItem(1L, 102L, 105L, "B001", new BigDecimal("4")))), 2L);
        transferService.submit(transfer.id(), 2L);
        transferService.approve(transfer.id(), 1L);

        assertThat(inventoryMapper.selectByDimension(1L, 102L, 1L, "B001").getQuantity())
                .isEqualByComparingTo("36.000");
        assertThat(inventoryMapper.selectByDimension(1L, 105L, 1L, "B001").getQuantity())
                .isEqualByComparingTo("4.000");
        assertThat(logCount(transfer.orderNo(), "TRANSFER_OUT")).isEqualTo(1);
        assertThat(logCount(transfer.orderNo(), "TRANSFER_IN")).isEqualTo(1);
    }

    @Test
    void transferRejectsInsufficientSourceStock() {
        OrderViews.CreatedOrder transfer = transferService.create(new OrderRequests.Transfer(1L, 2L, "跨仓移库",
                List.of(new OrderRequests.TransferItem(2L, 102L, 201L, "B002", new BigDecimal("999")))), 2L);
        transferService.submit(transfer.id(), 2L);

        assertThatThrownBy(() -> transferService.approve(transfer.id(), 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("移库源库存不足");
        assertThat(transferService.detail(transfer.id()).order().getStatus())
                .isEqualTo(OrderStatus.SUBMITTED.code());
    }

    private OrderRequests.Freeze freezeRequest(String opType, String quantity) {
        return new OrderRequests.Freeze(1L, "冻结测试",
                List.of(new OrderRequests.FreezeItem(1L, 102L, "B001", opType, new BigDecimal(quantity), "测试")));
    }

    private long logCount(String orderNo, String bizType) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wms_inventory_log WHERE order_no = ? AND biz_type = ?",
                Long.class, orderNo, bizType);
        return count == null ? 0 : count;
    }
}
