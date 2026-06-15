package com.blackboard.navigator;

import com.blackboard.model.Position;
import com.blackboard.model.RouteAlgorithm;
import com.blackboard.navigator.service.RoutePlannerService;
import com.blackboard.navigator.service.RoutePlanningResult;

import java.util.Set;

/**
 * 不依赖 Redis/RabbitMQ 的简单自测，可直接在 Eclipse 中 Run As Java Application。
 */
public class NavigatorAlgorithmSelfTest {
    public static void main(String[] args) {
        RoutePlannerService service = new RoutePlannerService();
        Position start = new Position(0, 0);
        Position target = new Position(4, 4);
        Set<Position> blocks = Set.of(new Position(1, 0), new Position(1, 1), new Position(1, 2));

        RoutePlanningResult bfs = service.planWithMetrics(RouteAlgorithm.BFS, start, target, 5, 5, blocks);
        RoutePlanningResult astar = service.planWithMetrics(RouteAlgorithm.A_STAR, start, target, 5, 5, blocks);
        RoutePlanningResult weighted = service.planWithMetrics(RouteAlgorithm.WEIGHTED_A_STAR, start, target, 5, 5, blocks);

        System.out.println("BFS route: " + bfs.getRoute());
        System.out.println("A* route: " + astar.getRoute());
        System.out.println("Weighted A* route: " + weighted.getRoute());
        System.out.println("BFS length=" + bfs.getRoute().size() + ", visited=" + bfs.getVisitedNodes() + ", elapsedMs=" + bfs.getElapsedMillis());
        System.out.println("A* length=" + astar.getRoute().size() + ", visited=" + astar.getVisitedNodes() + ", elapsedMs=" + astar.getElapsedMillis());
        System.out.println("Weighted A* length=" + weighted.getRoute().size() + ", visited=" + weighted.getVisitedNodes() + ", elapsedMs=" + weighted.getElapsedMillis());

        if (!bfs.isFound() || !astar.isFound() || !weighted.isFound()) {
            throw new IllegalStateException("Path should be found");
        }
        if (bfs.getRoute().size() != astar.getRoute().size()) {
            throw new IllegalStateException("BFS and A* should have same shortest length");
        }
        System.out.println("NavigatorAlgorithmSelfTest passed.");
    }
}
