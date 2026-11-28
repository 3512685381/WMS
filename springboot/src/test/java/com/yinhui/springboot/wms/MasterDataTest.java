package com.yinhui.springboot.wms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yinhui.springboot.common.exception.ApiException;
import com.yinhui.springboot.wms.dto.MasterRequests;
import com.yinhui.springboot.wms.entity.WmsWarehouse;
import com.yinhui.springboot.wms.mapper.WmsWarehouseMapper;
import com.yinhui.springboot.wms.service.CategoryService;
import com.yinhui.springboot.wms.service.PartnerService;
import com.yinhui.springboot.wms.service.ProductService;
import com.yinhui.springboot.wms.service.WarehouseService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MasterDataTest {

    @Autowired
    private WarehouseService warehouseService;
    @Autowired
    private ProductService productService;
    @Autowired
    private CategoryService categoryService;
    @Autowired
    private PartnerService partnerService;
    @Autowired
    private WmsWarehouseMapper warehouseMapper;

    @Test
    void warehouseCodeMustBeUniqueAndReferencedWarehouseCannotBeDeleted() {
        assertThatThrownBy(() -> warehouseService.createWarehouse(
                new MasterRequests.Warehouse("WH-CENTRAL", "重复仓库", null, null, 1), 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("编码已存在");

        assertThatThrownBy(() -> warehouseService.deleteWarehouse(1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("无法删除");
    }

    @Test
    void newlyCreatedWarehouseCanBeUpdatedAndDeleted() {
        long id = warehouseService.createWarehouse(
                new MasterRequests.Warehouse("WH-NEW", "新仓", "地址", "负责人", 1), 1L);
        warehouseService.updateWarehouse(id, new MasterRequests.Warehouse(
                "WH-NEW", "新仓改名", null, null, 0), 1L);
        WmsWarehouse warehouse = warehouseMapper.selectById(id);
        assertThat(warehouse.getName()).isEqualTo("新仓改名");
        assertThat(warehouse.getStatus()).isZero();

        warehouseService.deleteWarehouse(id);
        assertThat(warehouseMapper.selectById(id)).isNull();
    }

    @Test
    void locationMustBelongToTheSameWarehouseAsArea() {
        assertThatThrownBy(() -> warehouseService.createLocation(
                new MasterRequests.Location(2L, 12L, "BAD-01", BigDecimal.ZERO, 1), 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("库区不属于指定仓库");
    }

    @Test
    void areaWithLocationsCannotBeDeleted() {
        assertThatThrownBy(() -> warehouseService.deleteArea(12L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("库区下已有库位");
    }

    @Test
    void productSkuMustBeUniqueAndInventoryLocksBatchMode() {
        assertThatThrownBy(() -> productService.create(new MasterRequests.Product(
                "CHG-20W", "重复 SKU", 11L, "规格", "件", 1,
                BigDecimal.ZERO, BigDecimal.ZERO, 0L, 1L, BigDecimal.ZERO), 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("SKU 编码已存在");

        assertThatThrownBy(() -> productService.update(1L, new MasterRequests.Product(
                "CHG-20W", "20W 充电器", 11L, "USB-C PD 20W", "件", 0,
                new BigDecimal("30"), new BigDecimal("120"), 0L, 1L, new BigDecimal("29.9")), 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不能修改批次管理方式");
    }

    @Test
    void categoryCannotMoveUnderItsOwnChild() {
        assertThatThrownBy(() -> categoryService.update(1L,
                new MasterRequests.Category(11L, "电子配件", 10), 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("子分类");
    }

    @Test
    void supplierWithOrdersCannotBeDeleted() {
        assertThatThrownBy(() -> partnerService.delete(PartnerService.PartnerType.SUPPLIER, 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("无法删除");
    }
}
