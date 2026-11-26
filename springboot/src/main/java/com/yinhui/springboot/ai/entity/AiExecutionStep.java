package com.yinhui.springboot.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yinhui.springboot.common.entity.AuditedEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("ai_execution_step")
public class AiExecutionStep extends AuditedEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long messageId;

    private Long stepOrder;

    private String eventType;

    private String agentName;

    private String toolName;

    /** MySQL JSON 的原始文本。 */
    private String toolArgs;

    private String resultSummary;

    private Long durationMs;

    private String status;
}
