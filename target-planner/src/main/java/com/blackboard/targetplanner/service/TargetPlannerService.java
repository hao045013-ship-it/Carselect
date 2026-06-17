package com.blackboard.targetplanner.service;

import com.blackboard.api.Blackboard;
import com.blackboard.model.Position;
import com.blackboard.targetplanner.util.TargetPlannerDataReader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
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

    public List<TargetSelectionResult> assignTargets(List<String> carIds) {
        List<TargetSelectionResult> results = new ArrayList<>();
        if (carIds == null || carIds.isEmpty()) {
            return results;
        }

        int width = board.getMapWidth();
        int height = board.getMapHeight();
        boolean[] explored = board.getFullMapView();
        Set<Position> occupied = reader.readAllCarPositions();
        Set<Position> reservedTargets = new HashSet<>(reader.readReservedTargets(null));
        Set<Position> knownObstacles = reader.readKnownStaticObstacles(width, height);
        List<String> allCarIds = new ArrayList<>(reader.readCarIds());
        Collections.sort(allCarIds);
        int partitionCount = Math.max(allCarIds.size(), carIds.size());
        List<Region> regions = buildRegions(width, height, partitionCount);

        for (int i = 0; i < carIds.size(); i++) {
            String carId = carIds.get(i);
            if (carId == null || carId.isBlank()) {
                continue;
            }

            TargetSelectionResult result;
            try {
                Position carPosition = reader.readPosition(carId);
                TargetSelectionContext context = new TargetSelectionContext(
                        carId, carPosition, explored, width, height, occupied, reservedTargets, knownObstacles,
                        selectRegionForCar(carId, i, allCarIds, regions));
                TargetSelectionDecision decision = selector.select(context);

                if (decision.isAssigned()) {
                    Position target = decision.getTarget();
                    board.setTarget(carId, target.getX(), target.getY());
                    reservedTargets.add(target);
                    result = TargetSelectionResult.assigned(carId, target, decision.getMetrics());
                } else {
                    board.clearTarget(carId);
                    result = TargetSelectionResult.notAssigned(carId, decision.getReason(), decision.getMetrics());
                }
            } catch (RuntimeException e) {
                board.clearTarget(carId);
                result = TargetSelectionResult.notAssigned(carId, "error:" + e.getClass().getSimpleName());
            }
            results.add(result);
        }

        return results;
    }

    private Region selectRegionForCar(String carId, int fallbackIndex, List<String> allCarIds, List<Region> regions) {
        if (regions.isEmpty()) {
            return null;
        }
        int index = allCarIds.indexOf(carId);
        if (index < 0) {
            index = fallbackIndex;
        }
        return regions.get(index % regions.size());
    }

    private List<Region> buildRegions(int width, int height, int carCount) {
        List<Region> regions = new ArrayList<>();
        if (width <= 0 || height <= 0 || carCount <= 1) {
            return regions;
        }

        int cols = (int) Math.ceil(Math.sqrt(carCount * (width / (double) height)));
        cols = Math.max(1, Math.min(cols, carCount));
        int rows = (int) Math.ceil(carCount / (double) cols);

        for (int row = 0; row < rows; row++) {
            int minY = row * height / rows;
            int maxY = (row + 1) * height / rows;
            for (int col = 0; col < cols; col++) {
                if (regions.size() >= carCount) {
                    return regions;
                }
                int minX = col * width / cols;
                int maxX = (col + 1) * width / cols;
                regions.add(new Region(minX, minY, maxX, maxY));
            }
        }
        return regions;
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
