package com.yinhui.springboot.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yinhui.springboot.common.entity.AuditedEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("ai_session")
public class AiSession extends AuditedEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private Long userId;

    private String title;

    private String contextSummary;
}
