package com.yinhui.springboot.wms.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yinhui.springboot.common.exception.ApiException;
import com.yinhui.springboot.wms.dto.MasterRequests;
import com.yinhui.springboot.wms.dto.MasterViews;
import com.yinhui.springboot.wms.dto.PageResult;
import com.yinhui.springboot.wms.entity.WmsCustomer;
import com.yinhui.springboot.wms.entity.WmsSupplier;
import com.yinhui.springboot.wms.mapper.WmsCustomerMapper;
import com.yinhui.springboot.wms.mapper.WmsSupplierMapper;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 供应商与客户。编码全局唯一，删除前检查单据与商品默认供应商引用。
 */
@Service
public class PartnerService {

    public enum PartnerType {
        SUPPLIER, CUSTOMER
    }

    private final WmsSupplierMapper supplierMapper;
    private final WmsCustomerMapper customerMapper;
    private final ReferenceGuard guard;

    public PartnerService(WmsSupplierMapper supplierMapper, WmsCustomerMapper customerMapper,
            ReferenceGuard guard) {
        this.supplierMapper = supplierMapper;
        this.customerMapper = customerMapper;
        this.guard = guard;
    }

    public PageResult<MasterViews.PartnerRow> list(PartnerType type, String keyword, long page, long size) {
        String search = StringUtils.hasText(keyword) ? keyword.strip() : null;
        if (type == PartnerType.SUPPLIER) {
            IPage<WmsSupplier> source = supplierMapper.selectPage(new Page<>(page, size, true),
                    Wrappers.<WmsSupplier>lambdaQuery()
                            .and(search != null, wrapper -> wrapper.like(WmsSupplier::getCode, search)
                                    .or().like(WmsSupplier::getName, search))
                            .orderByAsc(WmsSupplier::getId));
            List<MasterViews.PartnerRow> rows = source.getRecords().stream()
                    .map(PartnerService::toRow).toList();
            return PageResult.of(rows, source.getTotal(), source.getCurrent(), source.getSize());
        }
        IPage<WmsCustomer> source = customerMapper.selectPage(new Page<>(page, size, true),
                Wrappers.<WmsCustomer>lambdaQuery()
                        .and(search != null, wrapper -> wrapper.like(WmsCustomer::getCode, search)
                                .or().like(WmsCustomer::getName, search))
                        .orderByAsc(WmsCustomer::getId));
        List<MasterViews.PartnerRow> rows = source.getRecords().stream()
                .map(PartnerService::toRow).toList();
        return PageResult.of(rows, source.getTotal(), source.getCurrent(), source.getSize());
    }

    public MasterViews.PartnerRow detail(PartnerType type, long id) {
        if (type == PartnerType.SUPPLIER) {
            WmsSupplier supplier = supplierMapper.selectById(id);
            if (supplier == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "供应商不存在");
            }
            return toRow(supplier);
        }
        WmsCustomer customer = customerMapper.selectById(id);
        if (customer == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "客户不存在");
        }
        return toRow(customer);
    }

    @Transactional
    public long create(PartnerType type, MasterRequests.Partner request, long userId) {
        String code = request.code().strip();
        ensureCode(type, code, null);
        if (type == PartnerType.SUPPLIER) {
            WmsSupplier supplier = new WmsSupplier();
            supplier.setCode(code);
            supplier.setName(request.name().strip());
            supplier.setContact(trim(request.contact()));
            supplier.setPhone(trim(request.phone()));
            supplier.setAddress(trim(request.address()));
            supplier.setCreatedBy(userId);
            supplierMapper.insert(supplier);
            return supplier.getId();
        }
        WmsCustomer customer = new WmsCustomer();
        customer.setCode(code);
        customer.setName(request.name().strip());
        customer.setContact(trim(request.contact()));
        customer.setPhone(trim(request.phone()));
        customer.setAddress(trim(request.address()));
        customer.setCreatedBy(userId);
        customerMapper.insert(customer);
        return customer.getId();
    }

    @Transactional
    public void update(PartnerType type, long id, MasterRequests.Partner request, long userId) {
        detail(type, id);
        String code = request.code().strip();
        ensureCode(type, code, id);
        if (type == PartnerType.SUPPLIER) {
            supplierMapper.update(null, Wrappers.<WmsSupplier>lambdaUpdate()
                    .eq(WmsSupplier::getId, id)
                    .set(WmsSupplier::getCode, code)
                    .set(WmsSupplier::getName, request.name().strip())
                    .set(WmsSupplier::getContact, trim(request.contact()))
                    .set(WmsSupplier::getPhone, trim(request.phone()))
                    .set(WmsSupplier::getAddress, trim(request.address())));
            return;
        }
        customerMapper.update(null, Wrappers.<WmsCustomer>lambdaUpdate()
                .eq(WmsCustomer::getId, id)
                .set(WmsCustomer::getCode, code)
                .set(WmsCustomer::getName, request.name().strip())
                .set(WmsCustomer::getContact, trim(request.contact()))
                .set(WmsCustomer::getPhone, trim(request.phone()))
                .set(WmsCustomer::getAddress, trim(request.address())));
    }

    @Transactional
    public void delete(PartnerType type, long id) {
        detail(type, id);
        if (type == PartnerType.SUPPLIER) {
            guard.requireUnused(id, "供应商已被入库单或商品引用，无法删除",
                    "wms_inbound_order.supplier_id", "wms_product.default_supplier_id");
            supplierMapper.deleteById(id);
            return;
        }
        guard.requireUnused(id, "客户已被出库单引用，无法删除", "wms_outbound_order.customer_id");
        customerMapper.deleteById(id);
    }

    private void ensureCode(PartnerType type, String code, Long excludeId) {
        if (type == PartnerType.SUPPLIER) {
            Long count = supplierMapper.selectCount(Wrappers.<WmsSupplier>lambdaQuery()
                    .eq(WmsSupplier::getCode, code)
                    .ne(excludeId != null, WmsSupplier::getId, excludeId));
            if (count != null && count > 0) {
                throw new ApiException(HttpStatus.CONFLICT, "供应商编码已存在：" + code);
            }
            return;
        }
        Long count = customerMapper.selectCount(Wrappers.<WmsCustomer>lambdaQuery()
                .eq(WmsCustomer::getCode, code)
                .ne(excludeId != null, WmsCustomer::getId, excludeId));
        if (count != null && count > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "客户编码已存在：" + code);
        }
    }

    private static MasterViews.PartnerRow toRow(WmsSupplier supplier) {
        MasterViews.PartnerRow row = new MasterViews.PartnerRow();
        row.setId(supplier.getId());
        row.setCode(supplier.getCode());
        row.setName(supplier.getName());
        row.setContact(supplier.getContact());
        row.setPhone(supplier.getPhone());
        row.setAddress(supplier.getAddress());
        row.setUpdatedAt(supplier.getUpdatedAt());
        return row;
    }

    private static MasterViews.PartnerRow toRow(WmsCustomer customer) {
        MasterViews.PartnerRow row = new MasterViews.PartnerRow();
        row.setId(customer.getId());
        row.setCode(customer.getCode());
        row.setName(customer.getName());
        row.setContact(customer.getContact());
        row.setPhone(customer.getPhone());
        row.setAddress(customer.getAddress());
        row.setUpdatedAt(customer.getUpdatedAt());
        return row;
    }

    private static String trim(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }
}
