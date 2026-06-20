package com.blackboard.targetplanner.service;

import com.blackboard.model.Position;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 贪心目标选择器。
 *
 * <p>该类只处理算法，不依赖 Redis/RabbitMQ，后续需求变化时可以独立替换目标评分函数。</p>
 */
public class GreedyTargetSelector implements TargetSelector {
    private static final int DEFAULT_CONGESTION_RADIUS = 2;
    private static final double DEFAULT_REGION_OUTSIDE_PENALTY = 4.0;

    private final String strategyName;
    private final double distanceWeight;
    private final double informationGainWeight;
    private final double congestionWeight;
    private final int congestionRadius;
    private final double regionOutsidePenalty;

    public GreedyTargetSelector() {
        this("GREEDY", 1.0, 0.0, 3.0, DEFAULT_CONGESTION_RADIUS);
    }

    protected GreedyTargetSelector(String strategyName,
                                   double distanceWeight,
                                   double informationGainWeight,
                                   double congestionWeight,
                                   int congestionRadius) {
        this(strategyName, distanceWeight, informationGainWeight, congestionWeight,
                congestionRadius, DEFAULT_REGION_OUTSIDE_PENALTY);
    }

    protected GreedyTargetSelector(String strategyName,
                                   double distanceWeight,
                                   double informationGainWeight,
                                   double congestionWeight,
                                   int congestionRadius,
                                   double regionOutsidePenalty) {
        this.strategyName = strategyName;
        this.distanceWeight = distanceWeight;
        this.informationGainWeight = informationGainWeight;
        this.congestionWeight = congestionWeight;
        this.congestionRadius = congestionRadius;
        this.regionOutsidePenalty = regionOutsidePenalty;
    }

    /**
     * 保留旧方法，避免已有自测或外部代码直接调用 select(...) 时受到影响。
     */
    public Position select(Position carPosition,
                           boolean[] explored,
                           int mapWidth,
                           int mapHeight,
                           Set<Position> occupiedByCars,
                           Set<Position> reservedTargets) {
        TargetSelectionContext context = new TargetSelectionContext(
                "unknown", carPosition, explored, mapWidth, mapHeight, occupiedByCars, reservedTargets);
        return select(context).getTarget();
    }

    @Override
    public TargetSelectionDecision select(TargetSelectionContext context) {
        long begin = System.nanoTime();
        List<Position> frontier = frontierCandidates(context.getExplored(), context.getMapWidth(), context.getMapHeight());
        List<Position> candidates = frontier.isEmpty()
                ? allUnexploredCandidates(context.getExplored(), context.getMapWidth(), context.getMapHeight())
                : frontier;

        int candidateCount = candidates.size();
        if (candidateCount == 0) {
            TargetSelectionMetrics metrics = metrics(frontier.size(), 0, 0, context, Double.NaN, 0, begin);
            return TargetSelectionDecision.notAssigned("no_unexplored_candidate", metrics);
        }

        List<Position> filtered = candidates.stream()
                .filter(p -> !context.getReservedTargets().contains(p))
                .filter(p -> !context.getOccupiedByCars().contains(p))
                .filter(p -> !context.getKnownObstacles().contains(p))
                .toList();

        if (filtered.isEmpty()) {
            TargetSelectionMetrics metrics = metrics(frontier.size(), candidateCount, 0, context, Double.NaN, 0, begin);
            return TargetSelectionDecision.notAssigned("all_candidates_reserved_occupied_or_known_obstacle", metrics);
        }

        Position selected = filtered.stream()
                .min(Comparator
                        .comparingDouble((Position p) -> score(context, p))
                        .thenComparingInt(Position::getY)
                        .thenComparingInt(Position::getX))
                .orElse(null);

        double selectedScore = selected == null ? Double.NaN : score(context, selected);
        int gain = selected == null ? 0 : informationGain(selected, context.getExplored(), context.getMapWidth(), context.getMapHeight());
        TargetSelectionMetrics metrics = metrics(frontier.size(), candidateCount, filtered.size(), context, selectedScore, gain, begin);
        if (selected == null) {
            return TargetSelectionDecision.notAssigned("no_candidate_after_scoring", metrics);
        }
        return TargetSelectionDecision.assigned(selected, metrics);
    }

    protected double score(TargetSelectionContext context, Position candidate) {
        double baseScore = score(
                context.getCarPosition(),
                candidate,
                context.getOccupiedByCars(),
                context.getExplored(),
                context.getMapWidth(),
                context.getMapHeight());
        if (context.getPreferredRegion() == null || context.getPreferredRegion().contains(candidate)) {
            return baseScore;
        }
        return baseScore + regionOutsidePenalty;
    }

    protected double score(Position carPosition,
                           Position candidate,
                           Set<Position> occupiedByCars,
                           boolean[] explored,
                           int mapWidth,
                           int mapHeight) {
        double distance = carPosition.manhattanDistance(candidate);
        double gain = informationGain(candidate, explored, mapWidth, mapHeight);
        double congestion = congestionPenalty(candidate, occupiedByCars);
        return distanceWeight * distance - informationGainWeight * gain + congestionWeight * congestion;
    }

    protected int informationGain(Position candidate, boolean[] explored, int mapWidth, int mapHeight) {
        int gain = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int nx = candidate.getX() + dx;
                int ny = candidate.getY() + dy;
                if (nx < 0 || nx >= mapWidth || ny < 0 || ny >= mapHeight) continue;
                if (!isExplored(explored, nx, ny, mapWidth)) {
                    gain++;
                }
            }
        }
        return gain;
    }

    private int congestionPenalty(Position candidate, Set<Position> occupiedByCars) {
        int congestion = 0;
        for (Position car : occupiedByCars) {
            if (candidate.manhattanDistance(car) <= congestionRadius) {
                congestion++;
            }
        }
        return congestion;
    }

    /** 前沿点：未探索，且四邻域中至少有一个已探索格子。 */
    protected List<Position> frontierCandidates(boolean[] explored, int mapWidth, int mapHeight) {
        List<Position> result = new ArrayList<>();
        for (int y = 0; y < mapHeight; y++) {
            for (int x = 0; x < mapWidth; x++) {
                if (isExplored(explored, x, y, mapWidth)) continue;
                if (hasExploredNeighbor(explored, x, y, mapWidth, mapHeight)) {
                    result.add(new Position(x, y));
                }
            }
        }
        return result;
    }

    protected List<Position> allUnexploredCandidates(boolean[] explored, int mapWidth, int mapHeight) {
        List<Position> result = new ArrayList<>();
        for (int y = 0; y < mapHeight; y++) {
            for (int x = 0; x < mapWidth; x++) {
                if (!isExplored(explored, x, y, mapWidth)) {
                    result.add(new Position(x, y));
                }
            }
        }
        return result;
    }

    private boolean hasExploredNeighbor(boolean[] explored, int x, int y, int mapWidth, int mapHeight) {
        return isExplored(explored, x + 1, y, mapWidth, mapHeight)
                || isExplored(explored, x - 1, y, mapWidth, mapHeight)
                || isExplored(explored, x, y + 1, mapWidth, mapHeight)
                || isExplored(explored, x, y - 1, mapWidth, mapHeight);
    }

    private boolean isExplored(boolean[] explored, int x, int y, int mapWidth) {
        if (explored == null) return false;
        int idx = y * mapWidth + x;
        return idx >= 0 && idx < explored.length && explored[idx];
    }

    private boolean isExplored(boolean[] explored, int x, int y, int mapWidth, int mapHeight) {
        if (x < 0 || x >= mapWidth || y < 0 || y >= mapHeight) return false;
        return isExplored(explored, x, y, mapWidth);
    }

    private TargetSelectionMetrics metrics(int frontierCount,
                                           int candidateCount,
                                           int filteredCandidateCount,
                                           TargetSelectionContext context,
                                           double selectedScore,
                                           int selectedInformationGain,
                                           long beginNano) {
        return new TargetSelectionMetrics(
                strategyName,
                frontierCount,
                candidateCount,
                filteredCandidateCount,
                context.getOccupiedByCars() == null ? 0 : context.getOccupiedByCars().size(),
                context.getReservedTargets() == null ? 0 : context.getReservedTargets().size(),
                context.getKnownObstacles() == null ? 0 : context.getKnownObstacles().size(),
                selectedScore,
                selectedInformationGain,
                (System.nanoTime() - beginNano) / 1_000_000
        );
    }
}
