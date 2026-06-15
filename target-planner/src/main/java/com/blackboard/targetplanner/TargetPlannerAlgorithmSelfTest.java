package com.blackboard.targetplanner;

import com.blackboard.model.Position;
import com.blackboard.targetplanner.service.GreedyTargetSelector;
import com.blackboard.targetplanner.service.TargetSelectionContext;
import com.blackboard.targetplanner.service.TargetSelectionDecision;
import com.blackboard.targetplanner.service.WeightedGreedyTargetSelector;

import java.util.Set;

/**
 * 不依赖 Redis/RabbitMQ 的简单自测，可直接在 Eclipse 中 Run As Java Application。
 */
public class TargetPlannerAlgorithmSelfTest {
    public static void main(String[] args) {
        int width = 5;
        int height = 5;
        boolean[] explored = new boolean[width * height];
        explored[0] = true; // (0,0) 已探索
        explored[1] = true; // (1,0) 已探索
        explored[5] = true; // (0,1) 已探索

        TargetSelectionContext context = new TargetSelectionContext(
                "Car001",
                new Position(0, 0),
                explored,
                width,
                height,
                Set.of(new Position(0, 0)),
                Set.of()
        );

        TargetSelectionDecision greedy = new GreedyTargetSelector().select(context);
        TargetSelectionDecision weighted = new WeightedGreedyTargetSelector().select(context);

        System.out.println("Greedy selected: " + greedy.getTarget() + ", metrics=" + greedy.getMetrics().toMap());
        System.out.println("Weighted selected: " + weighted.getTarget() + ", metrics=" + weighted.getMetrics().toMap());

        if (!greedy.isAssigned() || !weighted.isAssigned()) {
            throw new IllegalStateException("Target should be assigned");
        }
        System.out.println("TargetPlannerAlgorithmSelfTest passed.");
    }
}
