package com.yinhui.springboot.wms.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yinhui.springboot.common.exception.ApiException;
import com.yinhui.springboot.wms.dto.MasterRequests;
import com.yinhui.springboot.wms.dto.MasterViews;
import com.yinhui.springboot.wms.dto.PageResult;
import com.yinhui.springboot.wms.entity.WmsArea;
import com.yinhui.springboot.wms.entity.WmsLocation;
import com.yinhui.springboot.wms.entity.WmsWarehouse;
import com.yinhui.springboot.wms.mapper.WmsAreaMapper;
import com.yinhui.springboot.wms.mapper.WmsLocationMapper;
import com.yinhui.springboot.wms.mapper.WmsQueryMapper;
import com.yinhui.springboot.wms.mapper.WmsWarehouseMapper;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 仓库、库区、库位。库位通过 (warehouse_id, area_id) 校验归属，停用后的仓库和库位不能再建单。
 */
@Service
public class WarehouseService {

    private final WmsWarehouseMapper warehouseMapper;
    private final WmsAreaMapper areaMapper;
    private final WmsLocationMapper locationMapper;
    private final WmsQueryMapper queryMapper;
    private final ReferenceGuard guard;

    public WarehouseService(WmsWarehouseMapper warehouseMapper, WmsAreaMapper areaMapper,
            WmsLocationMapper locationMapper, WmsQueryMapper queryMapper, ReferenceGuard guard) {
        this.warehouseMapper = warehouseMapper;
        this.areaMapper = areaMapper;
        this.locationMapper = locationMapper;
        this.queryMapper = queryMapper;
        this.guard = guard;
    }

    public PageResult<WmsWarehouse> listWarehouses(String keyword, Integer status, long page, long size) {
        String search = text(keyword);
        var query = Wrappers.<WmsWarehouse>lambdaQuery()
                .eq(status != null, WmsWarehouse::getStatus, status)
                .and(search != null, wrapper -> wrapper
                        .like(WmsWarehouse::getCode, search).or().like(WmsWarehouse::getName, search))
                .orderByAsc(WmsWarehouse::getId);
        return PageResult.of(warehouseMapper.selectPage(new Page<>(page, size, true), query));
    }

    public WmsWarehouse getWarehouse(long id) {
        WmsWarehouse warehouse = warehouseMapper.selectById(id);
        if (warehouse == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "仓库不存在");
        }
        return warehouse;
    }

    @Transactional
    public long createWarehouse(MasterRequests.Warehouse request, long userId) {
        String code = request.code().strip();
        ensureWarehouseCode(code, null);
        WmsWarehouse warehouse = new WmsWarehouse();
        warehouse.setCode(code);
        warehouse.setName(request.name().strip());
        warehouse.setAddress(text(request.address()));
        warehouse.setManager(text(request.manager()));
        warehouse.setStatus(request.status() == null ? 1 : request.status());
        warehouse.setCreatedBy(userId);
        warehouseMapper.insert(warehouse);
        return warehouse.getId();
    }

    @Transactional
    public void updateWarehouse(long id, MasterRequests.Warehouse request, long userId) {
        WmsWarehouse warehouse = getWarehouse(id);
        String code = request.code().strip();
        if (!code.equals(warehouse.getCode())) {
            if (isWarehouseReferenced(id)) {
                throw new ApiException(HttpStatus.CONFLICT, "仓库已被库存或业务单据引用，不能修改编码");
            }
            ensureWarehouseCode(code, id);
        }
        warehouseMapper.update(null, Wrappers.<WmsWarehouse>lambdaUpdate()
                .eq(WmsWarehouse::getId, id)
                .set(WmsWarehouse::getCode, code)
                .set(WmsWarehouse::getName, request.name().strip())
                .set(WmsWarehouse::getAddress, text(request.address()))
                .set(WmsWarehouse::getManager, text(request.manager()))
                .set(WmsWarehouse::getStatus, request.status() == null ? warehouse.getStatus() : request.status()));
    }

    @Transactional
    public void deleteWarehouse(long id) {
        getWarehouse(id);
        guard.requireUnused(id, "仓库已被库区、库存或业务单据引用，无法删除",
                "wms_area.warehouse_id", "wms_location.warehouse_id", "wms_inventory.warehouse_id",
                "wms_inbound_order.warehouse_id", "wms_outbound_order.warehouse_id",
                "wms_freeze_order.warehouse_id", "wms_transfer_order.from_warehouse_id",
                "wms_transfer_order.to_warehouse_id", "wms_stocktake_order.warehouse_id",
                "wms_adjust_order.warehouse_id");
        warehouseMapper.deleteById(id);
    }

    public List<MasterViews.AreaRow> listAreas(Long warehouseId, String keyword) {
        return queryMapper.selectAreas(warehouseId, text(keyword));
    }

    @Transactional
    public long createArea(MasterRequests.Area request, long userId) {
        getWarehouse(request.warehouseId());
        String code = request.code().strip();
        ensureAreaCode(request.warehouseId(), code, null);
        WmsArea area = new WmsArea();
        area.setWarehouseId(request.warehouseId());
        area.setCode(code);
        area.setName(request.name().strip());
        area.setAreaType(request.areaType());
        area.setCreatedBy(userId);
        areaMapper.insert(area);
        return area.getId();
    }

    @Transactional
    public void updateArea(long id, MasterRequests.Area request, long userId) {
        WmsArea area = requireArea(id);
        if (area.getWarehouseId().longValue() != request.warehouseId()) {
            Long locations = locationMapper.selectCount(Wrappers.<WmsLocation>lambdaQuery()
                    .eq(WmsLocation::getAreaId, id));
            if (locations != null && locations > 0) {
                throw new ApiException(HttpStatus.CONFLICT, "库区下已有库位，不能更换所属仓库");
            }
            getWarehouse(request.warehouseId());
            ensureAreaCode(request.warehouseId(), request.code().strip(), id);
        } else if (!request.code().strip().equals(area.getCode())) {
            ensureAreaCode(area.getWarehouseId(), request.code().strip(), id);
        }
        area.setWarehouseId(request.warehouseId());
        area.setCode(request.code().strip());
        area.setName(request.name().strip());
        area.setAreaType(request.areaType());
        areaMapper.updateById(area);
    }

    @Transactional
    public void deleteArea(long id) {
        requireArea(id);
        guard.requireUnused(id, "库区下已有库位，无法删除", "wms_location.area_id");
        areaMapper.deleteById(id);
    }

    public PageResult<MasterViews.LocationRow> listLocations(Long warehouseId, Long areaId, Integer status,
            String keyword, long page, long size) {
        return PageResult.of(queryMapper.selectLocations(
                new Page<>(page, size, true), warehouseId, areaId, status, text(keyword)));
    }

    @Transactional
    public long createLocation(MasterRequests.Location request, long userId) {
        getWarehouse(request.warehouseId());
        WmsArea area = requireArea(request.areaId());
        if (area.getWarehouseId().longValue() != request.warehouseId()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "库区不属于指定仓库");
        }
        String code = request.code().strip();
        ensureLocationCode(request.warehouseId(), code, null);
        WmsLocation location = new WmsLocation();
        location.setWarehouseId(request.warehouseId());
        location.setAreaId(request.areaId());
        location.setCode(code);
        location.setMaxCapacity(request.maxCapacity() == null ? BigDecimal.ZERO : request.maxCapacity());
        location.setStatus(request.status() == null ? 1 : request.status());
        location.setCreatedBy(userId);
        locationMapper.insert(location);
        return location.getId();
    }

    @Transactional
    public void updateLocation(long id, MasterRequests.Location request, long userId) {
        WmsLocation location = requireLocation(id);
        WmsArea area = requireArea(request.areaId());
        if (area.getWarehouseId().longValue() != request.warehouseId()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "库区不属于指定仓库");
        }
        if (location.getWarehouseId().longValue() != request.warehouseId()
                && guard.exists("wms_inventory", "location_id", id)) {
            throw new ApiException(HttpStatus.CONFLICT, "库位已有库存，不能更换所属仓库");
        }
        if (!request.code().strip().equals(location.getCode())
                || location.getWarehouseId().longValue() != request.warehouseId()) {
            ensureLocationCode(request.warehouseId(), request.code().strip(), id);
        }
        location.setWarehouseId(request.warehouseId());
        location.setAreaId(request.areaId());
        location.setCode(request.code().strip());
        if (request.maxCapacity() != null) {
            location.setMaxCapacity(request.maxCapacity());
        }
        if (request.status() != null) {
            location.setStatus(request.status());
        }
        locationMapper.updateById(location);
    }

    @Transactional
    public void deleteLocation(long id) {
        requireLocation(id);
        guard.requireUnused(id, "库位已被库存或业务单据引用，无法删除",
                "wms_inventory.location_id", "wms_inbound_item.location_id", "wms_outbound_item.location_id",
                "wms_freeze_item.location_id", "wms_transfer_item.from_location_id",
                "wms_transfer_item.to_location_id", "wms_stocktake_item.location_id",
                "wms_adjust_item.location_id");
        locationMapper.deleteById(id);
    }

    private boolean isWarehouseReferenced(long id) {
        return guard.exists("wms_area", "warehouse_id", id)
                || guard.exists("wms_inventory", "warehouse_id", id)
                || guard.exists("wms_inbound_order", "warehouse_id", id)
                || guard.exists("wms_outbound_order", "warehouse_id", id)
                || guard.exists("wms_stocktake_order", "warehouse_id", id);
    }

    private void ensureWarehouseCode(String code, Long excludeId) {
        Long count = warehouseMapper.selectCount(Wrappers.<WmsWarehouse>lambdaQuery()
                .eq(WmsWarehouse::getCode, code)
                .ne(excludeId != null, WmsWarehouse::getId, excludeId));
        if (count != null && count > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "仓库编码已存在：" + code);
        }
    }

    private void ensureAreaCode(long warehouseId, String code, Long excludeId) {
        Long count = areaMapper.selectCount(Wrappers.<WmsArea>lambdaQuery()
                .eq(WmsArea::getWarehouseId, warehouseId)
                .eq(WmsArea::getCode, code)
                .ne(excludeId != null, WmsArea::getId, excludeId));
        if (count != null && count > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "同一仓库下的库区编码已存在：" + code);
        }
    }

    private void ensureLocationCode(long warehouseId, String code, Long excludeId) {
        Long count = locationMapper.selectCount(Wrappers.<WmsLocation>lambdaQuery()
                .eq(WmsLocation::getWarehouseId, warehouseId)
                .eq(WmsLocation::getCode, code)
                .ne(excludeId != null, WmsLocation::getId, excludeId));
        if (count != null && count > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "同一仓库下的库位编码已存在：" + code);
        }
    }

    private WmsArea requireArea(long id) {
        WmsArea area = areaMapper.selectById(id);
        if (area == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "库区不存在：" + id);
        }
        return area;
    }

    private WmsLocation requireLocation(long id) {
        WmsLocation location = locationMapper.selectById(id);
        if (location == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "库位不存在");
        }
        return location;
    }

    private static String text(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }
}
