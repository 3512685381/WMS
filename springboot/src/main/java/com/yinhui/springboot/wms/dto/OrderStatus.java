package com.yinhui.springboot.wms.dto;

import com.yinhui.springboot.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * 六类作业单据共用状态：0 草稿，1 待审核，2 已生效，3 已驳回。
 */
public enum OrderStatus {

    DRAFT(0, "草稿"),
    SUBMITTED(1, "待审核"),
    POSTED(2, "已生效"),
    REJECTED(3, "已驳回");

    private final int code;
    private final String label;

    OrderStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    public boolean editable() {
        return this == DRAFT || this == REJECTED;
    }

    public boolean submitted() {
        return this == SUBMITTED;
    }

    public static OrderStatus of(Integer code) {
        if (code != null) {
            for (OrderStatus status : values()) {
                if (status.code == code) {
                    return status;
                }
            }
        }
        throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "单据状态数据异常");
    }

    public static String labelOf(Integer code) {
        return code == null ? "" : of(code).label();
    }
}
