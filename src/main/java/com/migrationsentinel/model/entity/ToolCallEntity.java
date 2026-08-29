package com.migrationsentinel.model.entity;

import com.migrationsentinel.model.enums.AgentRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One entry in an agent's trajectory: the tool it called, the arguments, and what the
 * tool returned. Persisted for every review so docs/AGENT_TRAJECTORIES.md and the
 * evaluation trajectories are a straight dump of this table.
 */
@Getter
@Setter
@Entity
@Table(name = "tool_call", indexes = {
        @Index(name = "ix_tool_call_review_job_id", columnList = "review_job_id")
})
public class ToolCallEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "review_job_id", nullable = false,
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_tool_call_review_job"))
    private ReviewJobEntity reviewJob;

    @Enumerated(EnumType.STRING)
    @Column(name = "agent_role", nullable = false, length = 16)
    private AgentRole agentRole;

    @Column(name = "step_no", nullable = false)
    private int stepNo;

    @Column(name = "tool_name", nullable = false, length = 64)
    private String toolName;

    @Column(name = "arguments_json", columnDefinition = "text")
    private String argumentsJson;

    @Column(name = "result_json", columnDefinition = "text")
    private String resultJson;

    @Column(name = "duration_ms")
    private long durationMs;

    @Column(nullable = false)
    private boolean ok = true;
}
