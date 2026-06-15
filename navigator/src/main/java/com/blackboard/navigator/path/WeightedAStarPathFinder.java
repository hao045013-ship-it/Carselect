package com.blackboard.navigator.path;

/**
 * Weighted A* 增强算法。
 *
 * <p>普通 A* 使用 f(n)=g(n)+h(n)。Weighted A* 使用 f(n)=g(n)+w*h(n)，
 * w 大于 1 时会更偏向目标方向，通常访问节点更少，但路径不一定严格最短。</p>
 *
 * <p>本实现还启用了动态障碍物软避让：其他小车当前位置仍然绝对不可进入，
 * 其他小车周围一格会增加代价，使路径尽量远离拥挤区域。</p>
 */
public class WeightedAStarPathFinder extends AStarPathFinder {

    public WeightedAStarPathFinder() {
        this(readWeight(), readPenalty());
    }

    public WeightedAStarPathFinder(double heuristicWeight, int nearDynamicObstaclePenalty) {
        super(heuristicWeight, nearDynamicObstaclePenalty);
    }

    private static double readWeight() {
        String raw = System.getenv("WEIGHTED_A_STAR_WEIGHT");
        if (raw == null || raw.isBlank()) return 1.5;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ignored) {
            return 1.5;
        }
    }

    private static int readPenalty() {
        String raw = System.getenv("DYNAMIC_AVOID_PENALTY");
        if (raw == null || raw.isBlank()) return 2;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return 2;
        }
    }
}
