package com.blackboard.navigator;

import com.blackboard.model.Position;
import com.blackboard.model.RouteAlgorithm;
import com.blackboard.navigator.service.RoutePlannerService;
import com.blackboard.navigator.service.RoutePlanningResult;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 路径规划算法对比实验。
 *
 * <p>这个类不依赖 Redis/RabbitMQ，主要用于验收展示：比较 BFS、A*、Weighted A* 的路径长度、
 * 访问节点数和耗时。</p>
 */
public class NavigatorBenchmarkTest {
    public static void main(String[] args) {
        RoutePlannerService service = new RoutePlannerService();
        runScenario(service, "Open 10x10", 10, 10, new Position(0, 0), new Position(9, 9), Set.of());

        Set<Position> wall = new LinkedHashSet<>();
        for (int y = 0; y < 9; y++) {
            wall.add(new Position(4, y));
        }
        runScenario(service, "Wall with gap", 10, 10, new Position(0, 0), new Position(9, 9), wall);

        Set<Position> crowded = new LinkedHashSet<>();
        crowded.add(new Position(2, 1));
        crowded.add(new Position(2, 2));
        crowded.add(new Position(2, 3));
        crowded.add(new Position(3, 3));
        crowded.add(new Position(4, 3));
        runScenario(service, "Crowded dynamic cars", 8, 8, new Position(0, 0), new Position(7, 7), crowded);
    }

    private static void runScenario(RoutePlannerService service,
                                    String name,
                                    int width,
                                    int height,
                                    Position start,
                                    Position target,
                                    Set<Position> dynamicBlocks) {
        System.out.println("\nScenario: " + name);
        for (RouteAlgorithm algorithm : RouteAlgorithm.values()) {
            RoutePlanningResult result = service.planWithMetrics(algorithm, start, target, width, height, dynamicBlocks);
            System.out.printf("%-16s found=%-5s length=%-3d visited=%-4d elapsedMs=%d route=%s%n",
                    algorithm,
                    result.isFound(),
                    result.getRoute().size(),
                    result.getVisitedNodes(),
                    result.getElapsedMillis(),
                    result.getRoute());
        }
    }
}
