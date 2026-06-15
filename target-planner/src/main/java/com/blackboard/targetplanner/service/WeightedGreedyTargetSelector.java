package com.blackboard.targetplanner.service;

/**
 * 加权贪心目标选择器。
 *
 * <p>评分函数综合考虑：</p>
 * <pre>
 * score = 距离权重 * distance
 *       - 探索收益权重 * informationGain
 *       + 拥挤惩罚权重 * congestion
 * </pre>
 *
 * <p>分数越低越优先。这样小车不会只选择最近点，也会倾向选择周围未知区域更多、
 * 其他小车较少的目标点。</p>
 */
public class WeightedGreedyTargetSelector extends GreedyTargetSelector {
    public WeightedGreedyTargetSelector() {
        super("WEIGHTED_GREEDY", readDouble("TARGET_DISTANCE_WEIGHT", 1.0),
                readDouble("TARGET_INFORMATION_GAIN_WEIGHT", 1.5),
                readDouble("TARGET_CONGESTION_WEIGHT", 3.0),
                readInt("TARGET_CONGESTION_RADIUS", 2));
    }

    private static double readDouble(String key, double defaultValue) {
        String raw = System.getenv(key);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static int readInt(String key, int defaultValue) {
        String raw = System.getenv(key);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }
}
