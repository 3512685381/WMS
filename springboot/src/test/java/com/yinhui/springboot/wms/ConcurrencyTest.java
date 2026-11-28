package com.yinhui.springboot.wms;

import static org.assertj.core.api.Assertions.assertThat;

import com.yinhui.springboot.common.exception.ApiException;
import com.yinhui.springboot.wms.dto.OrderRequests;
import com.yinhui.springboot.wms.dto.OrderStatus;
import com.yinhui.springboot.wms.dto.OrderViews;
import com.yinhui.springboot.wms.mapper.WmsInventoryMapper;
import com.yinhui.springboot.wms.service.InboundOrderService;
import com.yinhui.springboot.wms.service.OrderNumberService;
import com.yinhui.springboot.wms.service.OutboundOrderService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 并发场景专项测试。
 *
 * <p>这些用例必须看到其他线程提交的数据，因此类上不加 {@code @Transactional}，
 * 改为在每个用例内自行清理（删除本次创建的单据、流水与库存行），保证测试库可重复运行。
 */
@SpringBootTest
@ActiveProfiles("test")
class ConcurrencyTest {

    private static final long WAREHOUSE = 1L;
    private static final long LOCATION = 102L;
    private static final long PRODUCT = 1L;
    private static final long SUPPLIER = 1L;
    private static final long CUSTOMER = 1L;
    private static final long CREATOR = 2L;
    private static final long REVIEWER = 1L;

    @Autowired
    private InboundOrderService inboundService;
    @Autowired
    private OutboundOrderService outboundService;
    @Autowired
    private OrderNumberService numberService;
    @Autowired
    private WmsInventoryMapper inventoryMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    /** 本次用例创建的批次，用于在清理阶段定位库存行。 */
    private final List<String> batches = Collections.synchronizedList(new ArrayList<>());
    /** 本次用例创建的单号，用于在清理阶段删除单据与流水。 */
    private final List<String> orderNos = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void cleanUp() {
        for (String orderNo : List.copyOf(orderNos)) {
            jdbcTemplate.update("DELETE FROM wms_inventory_log WHERE order_no = ?", orderNo);
            List<Long> ids = jdbcTemplate.queryForList(
                    "SELECT id FROM wms_inbound_order WHERE order_no = ?", Long.class, orderNo);
            for (Long id : ids) {
                jdbcTemplate.update("DELETE FROM wms_inbound_item WHERE order_id = ?", id);
                jdbcTemplate.update("DELETE FROM wms_inbound_order WHERE id = ?", id);
            }
            List<Long> outboundIds = jdbcTemplate.queryForList(
                    "SELECT id FROM wms_outbound_order WHERE order_no = ?", Long.class, orderNo);
            for (Long id : outboundIds) {
                jdbcTemplate.update("DELETE FROM wms_outbound_item WHERE order_id = ?", id);
                jdbcTemplate.update("DELETE FROM wms_outbound_order WHERE id = ?", id);
            }
        }
        for (String batch : List.copyOf(batches)) {
            jdbcTemplate.update("""
                    DELETE FROM wms_inventory
                    WHERE warehouse_id = ? AND location_id = ? AND product_id = ? AND batch_no = ?
                    """, WAREHOUSE, LOCATION, PRODUCT, batch);
        }
        orderNos.clear();
        batches.clear();
    }

    /**
     * 多个审核人同时点“审核通过”，只能有一次成功，库存只加一次、流水只写一条。
     */
    @Test
    void concurrentApprovalPostsInventoryOnlyOnce() throws Exception {
        String batch = trackBatch("CC-AP");
        OrderViews.CreatedOrder created = inboundService.create(inbound(batch, "5"), CREATOR);
        orderNos.add(created.orderNo());
        inboundService.submit(created.id(), CREATOR);

        int threads = 4;
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                inboundService.approve(created.id(), REVIEWER);
                return "posted";
            });
        }
        List<Throwable> failures = runConcurrently(tasks);

        assertThat(threads - failures.size()).as("成功审核的次数").isEqualTo(1);
        assertThat(failures).hasSize(threads - 1).allSatisfy(error -> {
            assertThat(error).isInstanceOf(ApiException.class);
            assertThat(((ApiException) error).getStatus().value()).isEqualTo(409);
        });

        assertThat(inventoryMapper.selectByDimension(WAREHOUSE, LOCATION, PRODUCT, batch).getQuantity())
                .as("并发审核后库存只能加一次")
                .isEqualByComparingTo("5.000");
        assertThat(logCount(created.orderNo())).isEqualTo(1);
        assertThat(inboundService.detail(created.id()).order().getStatus())
                .isEqualTo(OrderStatus.POSTED.code());
    }

    /**
     * 出库单并发审核时，库存只扣减一次，且不会出现负库存。
     */
    @Test
    void concurrentOutboundApprovalDeductsStockOnlyOnce() throws Exception {
        String batch = trackBatch("CC-OB");
        OrderViews.CreatedOrder seed = inboundService.create(inbound(batch, "10"), CREATOR);
        orderNos.add(seed.orderNo());
        inboundService.submit(seed.id(), CREATOR);
        inboundService.approve(seed.id(), REVIEWER);

        OrderViews.CreatedOrder outbound = outboundService.create(
                new OrderRequests.Outbound(WAREHOUSE, CUSTOMER, "并发出库",
                        List.of(new OrderRequests.OutboundItem(PRODUCT, LOCATION, batch,
                                new BigDecimal("4"), new BigDecimal("29.9000")))),
                CREATOR);
        orderNos.add(outbound.orderNo());
        outboundService.submit(outbound.id(), CREATOR);

        int threads = 4;
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                outboundService.approve(outbound.id(), REVIEWER);
                return "posted";
            });
        }
        List<Throwable> failures = runConcurrently(tasks);

        assertThat(threads - failures.size()).as("成功审核的次数").isEqualTo(1);
        assertThat(inventoryMapper.selectByDimension(WAREHOUSE, LOCATION, PRODUCT, batch).getQuantity())
                .as("并发审核后库存只能扣一次")
                .isEqualByComparingTo("6.000");
        assertThat(logCount(outbound.orderNo())).isEqualTo(1);
    }

    /**
     * AI 侧与人工侧共用 wms_order_sequence：并发占号必须拿到互不相同的单号。
     */
    @Test
    void orderNumberAllocationIsUniqueUnderConcurrency() throws Exception {
        int manualThreads = 3;
        int agentThreads = 3;
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < manualThreads; i++) {
            tasks.add(() -> {
                String batch = trackBatch("CC-NO");
                OrderViews.CreatedOrder created = inboundService.create(inbound(batch, "1"), CREATOR);
                orderNos.add(created.orderNo());
                return created.orderNo();
            });
        }
        // 模拟 FastAPI 侧直接占用单号：与 numberService 同一张表、同一条语句
        for (int i = 0; i < agentThreads; i++) {
            tasks.add(() -> transactionTemplate.execute(status -> numberService.next("RK", CREATOR)));
        }

        List<String> allocated = runConcurrentlyCollecting(tasks);

        assertThat(allocated).hasSize(manualThreads + agentThreads);
        Set<String> distinct = new HashSet<>(allocated);
        assertThat(distinct).as("并发占号不能重复").hasSize(allocated.size());
        assertThat(allocated).allSatisfy(no -> assertThat(no).matches("^RK\\d{12}$"));
    }

    /**
     * 多明细单据中只要有一条失败，整单回滚：成功的那条也不能改库存。
     */
    @Test
    void multiItemOrderRollsBackWhenAnyItemFails() {
        String batch = trackBatch("CC-RB");
        OrderViews.CreatedOrder seed = inboundService.create(inbound(batch, "3"), CREATOR);
        orderNos.add(seed.orderNo());
        inboundService.submit(seed.id(), CREATOR);
        inboundService.approve(seed.id(), REVIEWER);

        String missing = trackBatch("CC-RB-MISS");
        OrderViews.CreatedOrder outbound = outboundService.create(
                new OrderRequests.Outbound(WAREHOUSE, CUSTOMER, "多明细回滚",
                        List.of(
                                new OrderRequests.OutboundItem(PRODUCT, LOCATION, batch,
                                        new BigDecimal("2"), new BigDecimal("29.9000")),
                                // 该批次没有库存，审核时必然失败
                                new OrderRequests.OutboundItem(PRODUCT, LOCATION, missing,
                                        new BigDecimal("2"), new BigDecimal("29.9000")))),
                CREATOR);
        orderNos.add(outbound.orderNo());
        outboundService.submit(outbound.id(), CREATOR);

        assertThatThrownByApprove(outbound.id());

        assertThat(inventoryMapper.selectByDimension(WAREHOUSE, LOCATION, PRODUCT, batch).getQuantity())
                .as("失败明细不应导致第一条明细被扣减")
                .isEqualByComparingTo("3.000");
        assertThat(logCount(outbound.orderNo())).isZero();
        assertThat(outboundService.detail(outbound.id()).order().getStatus())
                .isEqualTo(OrderStatus.SUBMITTED.code());
    }

    // ---------------------------------------------------------------- helpers

    private void assertThatThrownByApprove(long orderId) {
        try {
            outboundService.approve(orderId, REVIEWER);
            org.assertj.core.api.Assertions.fail("库存不足的出库单不应审核通过");
        } catch (ApiException error) {
            assertThat(error.getStatus().value()).isIn(400, 409);
        }
    }

    private String trackBatch(String prefix) {
        String batch = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        batches.add(batch);
        return batch;
    }

    private OrderRequests.Inbound inbound(String batch, String quantity) {
        return new OrderRequests.Inbound(WAREHOUSE, SUPPLIER, "并发用例",
                List.of(new OrderRequests.InboundItem(PRODUCT, LOCATION, batch, null, null,
                        new BigDecimal(quantity), new BigDecimal("12.5000"))));
    }

    private long logCount(String orderNo) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wms_inventory_log WHERE order_no = ?", Long.class, orderNo);
        return count == null ? 0 : count;
    }

    /** 同时放行所有任务，返回失败列表。 */
    private List<Throwable> runConcurrently(List<Callable<String>> tasks) throws Exception {
        Execution execution = runAll(tasks);
        return execution.failures();
    }

    private List<String> runConcurrentlyCollecting(List<Callable<String>> tasks) throws Exception {
        Execution execution = runAll(tasks);
        assertThat(execution.failures()).as("并发占号不应失败").isEmpty();
        return execution.results();
    }

    private Execution runAll(List<Callable<String>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<String> results = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (Callable<String> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            for (Future<String> future : futures) {
                try {
                    results.add(future.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException error) {
                    failures.add(error.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return new Execution(new ArrayList<>(results), new ArrayList<>(failures));
    }

    private record Execution(List<String> results, List<Throwable> failures) {
    }
}
