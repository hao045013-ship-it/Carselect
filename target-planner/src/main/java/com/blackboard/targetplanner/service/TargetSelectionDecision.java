package com.blackboard.targetplanner.service;

import com.blackboard.model.Position;

/**
 * 目标选择算法输出：目标点 + 统计信息 + 失败原因。
 */
public class TargetSelectionDecision {
    private final Position target;
    private final TargetSelectionMetrics metrics;
    private final String reason;

    private TargetSelectionDecision(Position target, TargetSelectionMetrics metrics, String reason) {
        this.target = target;
        this.metrics = metrics;
        this.reason = reason;
    }

    public static TargetSelectionDecision assigned(Position target, TargetSelectionMetrics metrics) {
        return new TargetSelectionDecision(target, metrics, "assigned");
    }

    public static TargetSelectionDecision notAssigned(String reason, TargetSelectionMetrics metrics) {
        return new TargetSelectionDecision(null, metrics, reason);
    }

    public boolean isAssigned() {
        return target != null;
    }

    public Position getTarget() {
        return target;
    }

    public TargetSelectionMetrics getMetrics() {
        return metrics;
    }

    public String getReason() {
        return reason;
    }
}
