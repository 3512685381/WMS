package com.yinhui.springboot.wms.service;

import com.yinhui.springboot.common.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 档案删除与关键字段修改前的引用检查。表名与列名只来自本类常量，不拼接外部输入。
 */
@Component
public class ReferenceGuard {

    private final JdbcTemplate jdbcTemplate;

    public ReferenceGuard(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean exists(String table, String column, long id) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ? AND is_deleted = 0",
                Long.class, id);
        return count != null && count > 0;
    }

    /**
     * 以单个编号检查多张引用表，便于调用处写成一行。
     */
    public void requireUnused(long id, String message, String... tableAndColumnPairs) {
        for (String pair : tableAndColumnPairs) {
            String[] parts = pair.split("\\.", 2);
            if (exists(parts[0], parts[1], id)) {
                throw new ApiException(HttpStatus.CONFLICT, message);
            }
        }
    }
}
