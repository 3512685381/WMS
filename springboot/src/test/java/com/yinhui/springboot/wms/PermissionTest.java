package com.yinhui.springboot.wms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yinhui.springboot.system.dto.LoginRequest;
import com.yinhui.springboot.system.service.AuthService;
import com.yinhui.springboot.wms.dto.OrderRequests;
import com.yinhui.springboot.wms.dto.OrderStatus;
import com.yinhui.springboot.wms.dto.OrderViews;
import com.yinhui.springboot.wms.service.InboundOrderService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 接口层权限校验：接口必须真的落到 {@code @PreAuthorize} 上，
 * 库管员（OPERATOR）可以建单、提交，但不能审核、不能维护基础档案。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PermissionTest {

    private static final long WAREHOUSE = 1L;
    private static final long LOCATION = 102L;
    private static final long PRODUCT = 1L;
    private static final long SUPPLIER = 1L;
    private static final String PASSWORD = "WmsDemo@2026";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthService authService;
    @Autowired
    private InboundOrderService inboundService;

    @Test
    void requestWithoutTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/wms/inventory"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/wms/inventory").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void operatorCanListInboundOrders() throws Exception {
        mockMvc.perform(get("/api/wms/inbound-orders").header(HttpHeaders.AUTHORIZATION, bearer("operator")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray());
    }

    @Test
    void operatorCannotApproveInboundOrder() throws Exception {
        OrderViews.CreatedOrder created = inboundService.create(inbound(), 2L);
        inboundService.submit(created.id(), 2L);

        mockMvc.perform(post("/api/wms/inbound-orders/" + created.id() + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, bearer("operator")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        assertThat(inboundService.detail(created.id()).order().getStatus())
                .as("越权请求不能改变单据状态")
                .isEqualTo(OrderStatus.SUBMITTED.code());
    }

    @Test
    void operatorCannotApproveAdjustOrder() throws Exception {
        mockMvc.perform(post("/api/wms/adjust-orders/1/approve")
                        .header(HttpHeaders.AUTHORIZATION, bearer("operator")))
                .andExpect(status().isForbidden());
    }

    @Test
    void operatorCannotCompleteStocktake() throws Exception {
        mockMvc.perform(post("/api/wms/stocktake-orders/1/complete")
                        .header(HttpHeaders.AUTHORIZATION, bearer("operator")))
                .andExpect(status().isForbidden());
    }

    @Test
    void operatorCannotCreateWarehouse() throws Exception {
        String body = "{\"code\":\"TMP-" + UUID.randomUUID().toString().substring(0, 6)
                + "\",\"name\":\"临时仓\"}";
        mockMvc.perform(post("/api/wms/warehouses")
                        .header(HttpHeaders.AUTHORIZATION, bearer("operator"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanApproveInboundOrder() throws Exception {
        OrderViews.CreatedOrder created = inboundService.create(inbound(), 2L);
        inboundService.submit(created.id(), 2L);

        mockMvc.perform(post("/api/wms/inbound-orders/" + created.id() + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, bearer("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertThat(inboundService.detail(created.id()).order().getStatus())
                .isEqualTo(OrderStatus.POSTED.code());
    }

    private String bearer(String username) {
        return "Bearer " + authService.login(new LoginRequest(username, PASSWORD)).token();
    }

    private OrderRequests.Inbound inbound() {
        String batch = "PERM-" + UUID.randomUUID().toString().substring(0, 8);
        return new OrderRequests.Inbound(WAREHOUSE, SUPPLIER, "权限测试",
                List.of(new OrderRequests.InboundItem(PRODUCT, LOCATION, batch, null, null,
                        new BigDecimal("1"), new BigDecimal("12.5000"))));
    }
}
