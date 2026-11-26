package com.yinhui.springboot.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yinhui.springboot.common.entity.AuditedEntity;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("ai_run")
public class AiRun extends AuditedEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;

    private Long userMessageId;

    private Long assistantMessageId;

    private String status;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private String errorMessage;
}
