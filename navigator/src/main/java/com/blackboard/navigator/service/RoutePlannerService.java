package com.blackboard.navigator.service;

import com.blackboard.model.Position;
import com.blackboard.model.RouteAlgorithm;
import com.blackboard.navigator.path.AStarPathFinder;
import com.blackboard.navigator.path.BfsPathFinder;
import com.blackboard.navigator.path.PathFinder;
import com.blackboard.navigator.path.PathSearchResult;
import com.blackboard.navigator.path.WeightedAStarPathFinder;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 路径规划服务，隔离算法选择逻辑。
 */
public class RoutePlannerService {
    private final Map<RouteAlgorithm, PathFinder> pathFinders = new EnumMap<>(RouteAlgorithm.class);

    public RoutePlannerService() {
        register(RouteAlgorithm.BFS, new BfsPathFinder());
        register(RouteAlgorithm.A_STAR, new AStarPathFinder());
        register(RouteAlgorithm.WEIGHTED_A_STAR, new WeightedAStarPathFinder());
    }

    public void register(RouteAlgorithm algorithm, PathFinder pathFinder) {
        pathFinders.put(algorithm, pathFinder);
    }

    public List<Position> plan(RouteAlgorithm algorithm,
                               Position start,
                               Position target,
                               int mapWidth,
                               int mapHeight,
                               Set<Position> dynamicBlocks) {
        return planWithMetrics(algorithm, start, target, mapWidth, mapHeight, dynamicBlocks).getRoute();
    }

    public RoutePlanningResult planWithMetrics(RouteAlgorithm algorithm,
                                               Position start,
                                               Position target,
                                               int mapWidth,
                                               int mapHeight,
                                               Set<Position> dynamicBlocks) {
        PathFinder finder = pathFinders.get(algorithm);
        if (finder == null) {
            throw new IllegalArgumentException("Unsupported route algorithm: " + algorithm);
        }
        PathSearchResult searchResult = finder.findPathWithMetrics(start, target, mapWidth, mapHeight, dynamicBlocks);
        return new RoutePlanningResult(
                algorithm,
                searchResult.getPath(),
                searchResult.isPathFound(),
                searchResult.getVisitedNodes(),
                searchResult.getElapsedMillis(),
                dynamicBlocks == null ? 0 : dynamicBlocks.size()
        );
    }
}
