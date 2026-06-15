package com.blackboard.targetplanner.service;

import com.blackboard.model.Position;

/**
 * 目标分配结果。
 */
public class TargetSelectionResult {
    private final String carId;
    private final Position target;
    private final boolean assigned;
    private final String reason;
    private final TargetSelectionMetrics metrics;

    private TargetSelectionResult(String carId,
                                  Position target,
                                  boolean assigned,
                                  String reason,
                                  TargetSelectionMetrics metrics) {
        this.carId = carId;
        this.target = target;
        this.assigned = assigned;
        this.reason = reason;
        this.metrics = metrics;
    }

    public static TargetSelectionResult assigned(String carId, Position target) {
        return new TargetSelectionResult(carId, target, true, "assigned", null);
    }

    public static TargetSelectionResult assigned(String carId, Position target, TargetSelectionMetrics metrics) {
        return new TargetSelectionResult(carId, target, true, "assigned", metrics);
    }

    public static TargetSelectionResult notAssigned(String carId, String reason) {
        return new TargetSelectionResult(carId, null, false, reason, null);
    }

    public static TargetSelectionResult notAssigned(String carId, String reason, TargetSelectionMetrics metrics) {
        return new TargetSelectionResult(carId, null, false, reason, metrics);
    }

    public String getCarId() {
        return carId;
    }

    public Position getTarget() {
        return target;
    }

    public boolean isAssigned() {
        return assigned;
    }

    public String getReason() {
        return reason;
    }

    public TargetSelectionMetrics getMetrics() {
        return metrics;
    }
}
