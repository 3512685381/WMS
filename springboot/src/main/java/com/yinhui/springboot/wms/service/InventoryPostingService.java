package com.yinhui.springboot.wms.service;

import com.yinhui.springboot.common.exception.ApiException;
import com.yinhui.springboot.wms.entity.WmsInventory;
import com.yinhui.springboot.wms.entity.WmsInventoryLog;
import com.yinhui.springboot.wms.mapper.WmsInventoryLogMapper;
import com.yinhui.springboot.wms.mapper.WmsInventoryMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 库存记账。所有方法都必须在调用方事务内执行：单据状态、库存余额和流水同事务提交。
 *
 * <p>并发控制：同一单据先锁单据行；库存按“仓库 + 库位 + 商品 + 批次”的固定顺序加锁，
 * 同一维度的多条明细先汇总再记一条流水。任何一条明细校验失败都会抛异常，整单回滚。</p>
 */
@Service
public class InventoryPostingService {

    private static final BigDecimal ZERO = new BigDecimal("0.000");
    private static final String NO_BATCH = "NO_BATCH";

    private final WmsInventoryMapper inventoryMapper;
    private final WmsInventoryLogMapper logMapper;

    public InventoryPostingService(WmsInventoryMapper inventoryMapper, WmsInventoryLogMapper logMapper) {
        this.inventoryMapper = inventoryMapper;
        this.logMapper = logMapper;
    }

    public record PostingLine(long locationId, long productId, String batchNo, BigDecimal quantity,
                              LocalDate productionDate, LocalDate expireDate) {
    }

    public record FreezeLine(long locationId, long productId, String batchNo, String opType,
                             BigDecimal quantity) {
    }

    public record TransferLine(long fromLocationId, long toLocationId, long productId, String batchNo,
                               BigDecimal quantity) {
    }

    public record AdjustLine(long locationId, long productId, String batchNo, BigDecimal delta) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void postInbound(long warehouseId, String orderNo, List<PostingLine> lines, long operatorId) {
        Map<Dim, BigDecimal> totals = new TreeMap<>();
        Map<Dim, Dates> dates = new HashMap<>();
        for (PostingLine line : lines) {
            Dim dim = new Dim(warehouseId, line.locationId(), line.productId(), batch(line.batchNo()));
            totals.merge(dim, quantity(line.quantity()), BigDecimal::add);
            mergeDates(dates, dim, line.productionDate(), line.expireDate());
        }
        Map<Dim, WmsInventory> locked = lockAll(totals.keySet(), operatorId);
        for (Map.Entry<Dim, BigDecimal> entry : totals.entrySet()) {
            apply(entry.getKey(), locked.get(entry.getKey()), entry.getValue(), ZERO,
                    "INBOUND", orderNo, operatorId, dates.get(entry.getKey()));
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void postOutbound(long warehouseId, String orderNo, List<PostingLine> lines, long operatorId) {
        Map<Dim, BigDecimal> totals = new TreeMap<>();
        for (PostingLine line : lines) {
            Dim dim = new Dim(warehouseId, line.locationId(), line.productId(), batch(line.batchNo()));
            totals.merge(dim, quantity(line.quantity()), BigDecimal::add);
        }
        Map<Dim, WmsInventory> locked = lockAll(totals.keySet(), operatorId);
        List<String> shortages = new ArrayList<>();
        for (Map.Entry<Dim, BigDecimal> entry : totals.entrySet()) {
            WmsInventory inventory = locked.get(entry.getKey());
            BigDecimal available = available(inventory);
            if (available.compareTo(entry.getValue()) < 0) {
                shortages.add(entry.getKey().label() + " 可用 " + plain(available) + "，需要 " + plain(entry.getValue()));
            }
        }
        if (!shortages.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "可用库存不足，出库单未生效：" + String.join("；", shortages));
        }
        for (Map.Entry<Dim, BigDecimal> entry : totals.entrySet()) {
            apply(entry.getKey(), locked.get(entry.getKey()), entry.getValue().negate(), ZERO,
                    "OUTBOUND", orderNo, operatorId, null);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void postFreeze(long warehouseId, String orderNo, List<FreezeLine> lines, long operatorId) {
        Map<FreezeKey, BigDecimal> totals = new TreeMap<>();
        Collection<Dim> dimensions = new LinkedHashSet<>();
        for (FreezeLine line : lines) {
            Dim dim = new Dim(warehouseId, line.locationId(), line.productId(), batch(line.batchNo()));
            totals.merge(new FreezeKey(dim, line.opType()), quantity(line.quantity()), BigDecimal::add);
            dimensions.add(dim);
        }
        Map<Dim, WmsInventory> locked = lockAll(dimensions, operatorId);
        List<String> problems = new ArrayList<>();
        for (Map.Entry<FreezeKey, BigDecimal> entry : totals.entrySet()) {
            WmsInventory inventory = locked.get(entry.getKey().dim());
            BigDecimal value = entry.getValue();
            if ("FREEZE".equals(entry.getKey().opType()) && available(inventory).compareTo(value) < 0) {
                problems.add(entry.getKey().dim().label() + " 可冻结 " + plain(available(inventory)) + "，需要 " + plain(value));
            }
            if ("UNFREEZE".equals(entry.getKey().opType())
                    && inventory.getFrozenQuantity().compareTo(value) < 0) {
                problems.add(entry.getKey().dim().label() + " 已冻结 " + plain(inventory.getFrozenQuantity())
                        + "，解冻 " + plain(value));
            }
        }
        if (!problems.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "冻结数量校验失败，单据未生效：" + String.join("；", problems));
        }
        for (Map.Entry<FreezeKey, BigDecimal> entry : totals.entrySet()) {
            boolean freeze = "FREEZE".equals(entry.getKey().opType());
            apply(entry.getKey().dim(), locked.get(entry.getKey().dim()), ZERO,
                    freeze ? entry.getValue() : entry.getValue().negate(),
                    entry.getKey().opType(), orderNo, operatorId, null);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void postTransfer(long fromWarehouseId, long toWarehouseId, String orderNo,
            List<TransferLine> lines, long operatorId) {
        Map<Dim, BigDecimal> fromTotals = new TreeMap<>();
        Map<Dim, BigDecimal> toTotals = new TreeMap<>();
        for (TransferLine line : lines) {
            String batchNo = batch(line.batchNo());
            fromTotals.merge(new Dim(fromWarehouseId, line.fromLocationId(), line.productId(), batchNo),
                    quantity(line.quantity()), BigDecimal::add);
            toTotals.merge(new Dim(toWarehouseId, line.toLocationId(), line.productId(), batchNo),
                    quantity(line.quantity()), BigDecimal::add);
        }
        Collection<Dim> all = new LinkedHashSet<>(fromTotals.keySet());
        all.addAll(toTotals.keySet());
        Map<Dim, WmsInventory> locked = lockAll(all, operatorId);

        List<String> shortages = new ArrayList<>();
        for (Map.Entry<Dim, BigDecimal> entry : fromTotals.entrySet()) {
            BigDecimal available = available(locked.get(entry.getKey()));
            if (available.compareTo(entry.getValue()) < 0) {
                shortages.add(entry.getKey().label() + " 可用 " + plain(available) + "，需要 " + plain(entry.getValue()));
            }
        }
        if (!shortages.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "移库源库存不足，单据未生效：" + String.join("；", shortages));
        }
        for (Map.Entry<Dim, BigDecimal> entry : fromTotals.entrySet()) {
            apply(entry.getKey(), locked.get(entry.getKey()), entry.getValue().negate(), ZERO,
                    "TRANSFER_OUT", orderNo, operatorId, null);
        }
        for (Map.Entry<Dim, BigDecimal> entry : toTotals.entrySet()) {
            apply(entry.getKey(), locked.get(entry.getKey()), entry.getValue(), ZERO,
                    "TRANSFER_IN", orderNo, operatorId, null);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void postAdjust(long warehouseId, String orderNo, List<AdjustLine> lines, long operatorId) {
        Map<Dim, BigDecimal> totals = new TreeMap<>();
        for (AdjustLine line : lines) {
            BigDecimal delta = quantity(line.delta());
            if (delta.signum() == 0) {
                continue;
            }
            Dim dim = new Dim(warehouseId, line.locationId(), line.productId(), batch(line.batchNo()));
            totals.merge(dim, delta, BigDecimal::add);
        }
        if (totals.isEmpty()) {
            return;
        }
        Map<Dim, WmsInventory> locked = lockAll(totals.keySet(), operatorId);
        for (Map.Entry<Dim, BigDecimal> entry : totals.entrySet()) {
            if (entry.getValue().signum() == 0) {
                continue;
            }
            apply(entry.getKey(), locked.get(entry.getKey()), entry.getValue(), ZERO,
                    "ADJUST", orderNo, operatorId, null);
        }
    }

    private Map<Dim, WmsInventory> lockAll(Collection<Dim> dimensions, long operatorId) {
        Map<Dim, WmsInventory> locked = new HashMap<>();
        for (Dim dim : new TreeSet<>(dimensions)) {
            locked.put(dim, lockOrCreate(dim, operatorId));
        }
        return locked;
    }

    private WmsInventory lockOrCreate(Dim dim, long operatorId) {
        WmsInventory inventory = inventoryMapper.selectForUpdate(
                dim.warehouseId(), dim.locationId(), dim.productId(), dim.batchNo());
        if (inventory != null) {
            return inventory;
        }
        WmsInventory created = new WmsInventory();
        created.setWarehouseId(dim.warehouseId());
        created.setLocationId(dim.locationId());
        created.setProductId(dim.productId());
        created.setBatchNo(dim.batchNo());
        created.setQuantity(ZERO);
        created.setFrozenQuantity(ZERO);
        created.setCreatedBy(operatorId);
        try {
            inventoryMapper.insert(created);
        } catch (DuplicateKeyException ignored) {
            // 另一个事务刚创建了同一维度，回读并锁定即可。
        }
        inventory = inventoryMapper.selectForUpdate(
                dim.warehouseId(), dim.locationId(), dim.productId(), dim.batchNo());
        if (inventory == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "库存维度创建失败，请重试");
        }
        return inventory;
    }

    private void apply(Dim dim, WmsInventory inventory, BigDecimal deltaQty, BigDecimal deltaFrozen,
            String bizType, String orderNo, long operatorId, Dates dates) {
        BigDecimal beforeQty = inventory.getQuantity();
        BigDecimal beforeFrozen = inventory.getFrozenQuantity();
        BigDecimal afterQty = beforeQty.add(deltaQty);
        BigDecimal afterFrozen = beforeFrozen.add(deltaFrozen);

        if (afterQty.signum() < 0) {
            throw new ApiException(HttpStatus.CONFLICT, "库存数量不足：" + dim.label());
        }
        if (afterFrozen.signum() < 0 || afterFrozen.compareTo(afterQty) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "冻结数量超出可用范围：" + dim.label());
        }

        if (dates != null && (dates.productionDate() != null || dates.expireDate() != null)) {
            LocalDate production = inventory.getProductionDate() != null
                    ? inventory.getProductionDate() : dates.productionDate();
            LocalDate expire = inventory.getExpireDate() != null ? inventory.getExpireDate() : dates.expireDate();
            if (production != null && expire != null && expire.isBefore(production)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "效期不得早于生产日期：" + dim.label());
            }
            inventoryMapper.fillDates(inventory.getId(), dates.productionDate(), dates.expireDate());
        }

        if (deltaQty.signum() == 0 && deltaFrozen.signum() == 0) {
            return;
        }
        if (inventoryMapper.updateQuantities(inventory.getId(), afterQty, afterFrozen) != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "库存更新失败，请重试：" + dim.label());
        }

        WmsInventoryLog log = new WmsInventoryLog();
        log.setInventoryId(inventory.getId());
        log.setWarehouseId(dim.warehouseId());
        log.setLocationId(dim.locationId());
        log.setProductId(dim.productId());
        log.setBatchNo(dim.batchNo());
        log.setBizType(bizType);
        log.setOrderNo(orderNo);
        log.setBeforeQty(beforeQty);
        log.setChangeQty(deltaQty);
        log.setAfterQty(afterQty);
        log.setBeforeFrozenQty(beforeFrozen);
        log.setChangeFrozenQty(deltaFrozen);
        log.setAfterFrozenQty(afterFrozen);
        log.setOperatorId(operatorId);
        log.setCreatedBy(operatorId);
        try {
            logMapper.insert(log);
        } catch (DuplicateKeyException error) {
            throw new ApiException(HttpStatus.CONFLICT, "该单据已记账，请勿重复审核");
        }

        inventory.setQuantity(afterQty);
        inventory.setFrozenQuantity(afterFrozen);
    }

    private static BigDecimal available(WmsInventory inventory) {
        return inventory.getQuantity().subtract(inventory.getFrozenQuantity());
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String batch(String batchNo) {
        if (batchNo == null || batchNo.isBlank()) {
            return NO_BATCH;
        }
        return batchNo.strip();
    }

    private static BigDecimal quantity(BigDecimal value) {
        if (value == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "数量不能为空");
        }
        try {
            return value.setScale(3, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException error) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "数量最多三位小数");
        }
    }

    private static void mergeDates(Map<Dim, Dates> dates, Dim dim, LocalDate productionDate, LocalDate expireDate) {
        if (productionDate == null && expireDate == null) {
            return;
        }
        Dates existing = dates.get(dim);
        if (existing == null) {
            dates.put(dim, new Dates(productionDate, expireDate));
            return;
        }
        if (productionDate != null && existing.productionDate() != null
                && !productionDate.equals(existing.productionDate())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "同一库存维度的生产日期不一致：" + dim.label());
        }
        if (expireDate != null && existing.expireDate() != null && !expireDate.equals(existing.expireDate())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "同一库存维度的效期不一致：" + dim.label());
        }
        dates.put(dim, new Dates(
                existing.productionDate() != null ? existing.productionDate() : productionDate,
                existing.expireDate() != null ? existing.expireDate() : expireDate));
    }

    private record Dates(LocalDate productionDate, LocalDate expireDate) {
    }

    private record Dim(long warehouseId, long locationId, long productId, String batchNo)
            implements Comparable<Dim> {

        @Override
        public int compareTo(Dim other) {
            int result = Long.compare(warehouseId, other.warehouseId);
            if (result != 0) {
                return result;
            }
            result = Long.compare(locationId, other.locationId);
            if (result != 0) {
                return result;
            }
            result = Long.compare(productId, other.productId);
            return result != 0 ? result : batchNo.compareTo(other.batchNo);
        }

        String label() {
            return "库位 " + locationId + " / 商品 " + productId + " / 批次 " + batchNo;
        }
    }

    private record FreezeKey(Dim dim, String opType) implements Comparable<FreezeKey> {

        @Override
        public int compareTo(FreezeKey other) {
            int result = dim.compareTo(other.dim);
            return result != 0 ? result : opType.compareTo(other.opType);
        }
    }
}
