package com.blackboard.targetplanner;

import com.blackboard.model.Position;
import com.blackboard.targetplanner.service.GreedyTargetSelector;
import com.blackboard.targetplanner.service.TargetSelectionContext;
import com.blackboard.targetplanner.service.TargetSelectionDecision;
import com.blackboard.targetplanner.service.TargetSelector;
import com.blackboard.targetplanner.service.WeightedGreedyTargetSelector;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 目标选择策略对比实验。
 *
 * <p>这个类不依赖 Redis/RabbitMQ，主要用于验收展示：比较普通贪心和加权贪心在不同场景下的选择差异。</p>
 */
public class TargetPlannerBenchmarkTest {
    public static void main(String[] args) {
        Map<String, TargetSelector> selectors = new LinkedHashMap<>();
        selectors.put("GREEDY", new GreedyTargetSelector());
        selectors.put("WEIGHTED_GREEDY", new WeightedGreedyTargetSelector());

        runScenario("Small frontier", selectors, smallFrontierContext());
        runScenario("Reserved target conflict", selectors, reservedConflictContext());
        runScenario("Car congestion", selectors, congestionContext());
    }

    private static void runScenario(String name, Map<String, TargetSelector> selectors, TargetSelectionContext context) {
        System.out.println("\nScenario: " + name);
        for (Map.Entry<String, TargetSelector> entry : selectors.entrySet()) {
            TargetSelectionDecision decision = entry.getValue().select(context);
            System.out.printf("%-18s assigned=%-5s target=%-8s metrics=%s%n",
                    entry.getKey(), decision.isAssigned(), decision.getTarget(), decision.getMetrics().toMap());
        }
    }

    private static TargetSelectionContext smallFrontierContext() {
        int width = 6;
        int height = 6;
        boolean[] explored = new boolean[width * height];
        setExplored(explored, width, 0, 0);
        setExplored(explored, width, 1, 0);
        setExplored(explored, width, 0, 1);
        return new TargetSelectionContext("Car001", new Position(0, 0), explored, width, height,
                Set.of(new Position(0, 0)), Set.of());
    }

    private static TargetSelectionContext reservedConflictContext() {
        int width = 6;
        int height = 6;
        boolean[] explored = new boolean[width * height];
        setExplored(explored, width, 0, 0);
        setExplored(explored, width, 1, 0);
        setExplored(explored, width, 0, 1);
        return new TargetSelectionContext("Car001", new Position(0, 0), explored, width, height,
                Set.of(new Position(0, 0)), Set.of(new Position(2, 0)));
    }

    private static TargetSelectionContext congestionContext() {
        int width = 8;
        int height = 8;
        boolean[] explored = new boolean[width * height];
        for (int y = 0; y <= 2; y++) {
            for (int x = 0; x <= 2; x++) {
                setExplored(explored, width, x, y);
            }
        }
        return new TargetSelectionContext("Car001", new Position(0, 0), explored, width, height,
                Set.of(new Position(0, 0), new Position(3, 2), new Position(2, 3)), Set.of());
    }

    private static void setExplored(boolean[] explored, int width, int x, int y) {
        explored[y * width + x] = true;
    }
}
