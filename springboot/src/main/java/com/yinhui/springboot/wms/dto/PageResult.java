package com.yinhui.springboot.wms.dto;

import com.baomidou.mybatisplus.core.metadata.IPage;
import java.util.List;

public record PageResult<T>(List<T> records, long total, long page, long size) {

    public static <T> PageResult<T> of(IPage<T> source) {
        return new PageResult<>(source.getRecords(), source.getTotal(), source.getCurrent(), source.getSize());
    }

    public static <T> PageResult<T> of(List<T> records, long total, long page, long size) {
        return new PageResult<>(records, total, page, size);
    }

    public static long safePage(long page) {
        return Math.max(page, 1);
    }

    public static long safeSize(long size) {
        return Math.min(Math.max(size, 1), 100);
    }
}
