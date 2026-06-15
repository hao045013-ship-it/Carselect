package com.blackboard.targetplanner.service;

import com.blackboard.api.Blackboard;
import com.blackboard.model.Position;
import com.blackboard.targetplanner.util.TargetPlannerDataReader;

import java.util.Locale;
import java.util.Set;

/**
 * 目标分配服务，隔离黑板读取与目标选择算法。
 */
public class TargetPlannerService {
    private final Blackboard board;
    private final TargetPlannerDataReader reader;
    private final TargetSelector selector;

    public TargetPlannerService(Blackboard board) {
        this.board = board;
        this.reader = new TargetPlannerDataReader(board);
        this.selector = createSelector();
    }

    public TargetSelectionResult assignTarget(String carId) {
        int width = board.getMapWidth();
        int height = board.getMapHeight();
        Position carPosition = reader.readPosition(carId);
        boolean[] explored = board.getFullMapView();
        Set<Position> occupied = reader.readAllCarPositions();
        Set<Position> reservedTargets = reader.readReservedTargets(carId);
        Set<Position> knownObstacles = reader.readKnownStaticObstacles(width, height);

        TargetSelectionContext context = new TargetSelectionContext(
                carId, carPosition, explored, width, height, occupied, reservedTargets, knownObstacles);
        TargetSelectionDecision decision = selector.select(context);
        if (!decision.isAssigned()) {
            board.clearTarget(carId);
            return TargetSelectionResult.notAssigned(carId, decision.getReason(), decision.getMetrics());
        }

        Position target = decision.getTarget();
        board.setTarget(carId, target.getX(), target.getY());
        return TargetSelectionResult.assigned(carId, target, decision.getMetrics());
    }

    private TargetSelector createSelector() {
        String strategy = System.getenv("TARGET_STRATEGY");
        if (strategy == null || strategy.isBlank()) {
            // 默认启用增强评分函数；如果想保持最朴素的贪心，可设置 TARGET_STRATEGY=GREEDY。
            return new WeightedGreedyTargetSelector();
        }
        String normalized = strategy.trim().toUpperCase(Locale.ROOT).replace("-", "_").replace(" ", "_");
        if ("GREEDY".equals(normalized) || "NEAREST".equals(normalized) || "NEAREST_FRONTIER".equals(normalized)) {
            return new GreedyTargetSelector();
        }
        if ("WEIGHTED".equals(normalized) || "WEIGHTED_GREEDY".equals(normalized) || "INFORMATION_GAIN".equals(normalized)) {
            return new WeightedGreedyTargetSelector();
        }
        throw new IllegalArgumentException("Unsupported TARGET_STRATEGY: " + strategy);
    }
}
