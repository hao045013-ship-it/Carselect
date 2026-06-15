package com.blackboard.navigator.service;

import com.blackboard.model.Position;
import com.blackboard.model.RouteAlgorithm;

import java.util.Collections;
import java.util.List;

/**
 * Navigator 服务层返回值：路径 + 统计指标。
 */
public class RoutePlanningResult {
    private final RouteAlgorithm algorithm;
    private final List<Position> route;
    private final boolean found;
    private final int visitedNodes;
    private final long elapsedMillis;
    private final int dynamicObstacleCount;

    public RoutePlanningResult(RouteAlgorithm algorithm,
                               List<Position> route,
                               boolean found,
                               int visitedNodes,
                               long elapsedMillis,
                               int dynamicObstacleCount) {
        this.algorithm = algorithm;
        this.route = route == null ? Collections.emptyList() : List.copyOf(route);
        this.found = found;
        this.visitedNodes = visitedNodes;
        this.elapsedMillis = elapsedMillis;
        this.dynamicObstacleCount = dynamicObstacleCount;
    }

    public RouteAlgorithm getAlgorithm() {
        return algorithm;
    }

    public List<Position> getRoute() {
        return route;
    }

    public boolean isFound() {
        return found;
    }

    public int getVisitedNodes() {
        return visitedNodes;
    }

    public long getElapsedMillis() {
        return elapsedMillis;
    }

    public int getDynamicObstacleCount() {
        return dynamicObstacleCount;
    }
}
