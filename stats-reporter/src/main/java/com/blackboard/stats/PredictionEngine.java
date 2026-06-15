package com.blackboard.stats;

import java.util.ArrayList;
import java.util.List;

/**
 * 预测引擎 —— 基于滑动窗口线性回归预测探索完成时间
 */
public class PredictionEngine {

    /** 滑动窗口最多保留的采样点数 */
    private static final int MAX_POINTS = 20;

    /** 每个元素是 [tick, exploredCells] */
    private final List<long[]> coveragePoints = new ArrayList<>();

    /**
     * 加入新采样点，超过窗口大小时删除最旧的
     */
    public void addPoint(long tick, int exploredCells) {
        coveragePoints.add(new long[]{tick, exploredCells});
        while (coveragePoints.size() > MAX_POINTS) {
            // Integration note: JDK 17 compatible replacement for List.removeFirst().
            coveragePoints.remove(0);
        }
    }

    /**
     * 清空历史采样数据
     */
    public void reset() {
        coveragePoints.clear();
    }

    /**
     * 基于当前采样点预测完成时间
     *
     * @param totalCells 地图总格子数
     * @return 预测结果
     */
    public PredictionResult predict(int totalCells) {
        return predictFromPoints(coveragePoints, totalCells);
    }

    /**
     * 对任意采样点列表执行线性回归预测（供 HTTP 接口复用）
     */
    public static PredictionResult predictFromPoints(List<long[]> points, int totalCells) {
        PredictionResult result = new PredictionResult();
        result.confidence = "low";
        result.estimatedRemainingTicks = -1;
        result.estimatedFinishTick = -1;
        result.rSquared = 0;

        if (points == null || points.size() < 3) {
            return result;
        }

        int n = points.size();
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (long[] p : points) {
            double x = p[0];
            double y = p[1];
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumX2 += x * x;
        }

        double meanX = sumX / n;
        double meanY = sumY / n;
        double denominator = sumX2 - n * meanX * meanX;

        if (Math.abs(denominator) < 1e-9) {
            return result;
        }

        double slope = (sumXY - n * meanX * meanY) / denominator;
        double intercept = meanY - slope * meanX;

        if (slope <= 0) {
            return result;
        }

        // Integration note: JDK 17 compatible replacement for List.getLast().
        long[] latest = points.get(points.size() - 1);
        long latestTick = latest[0];
        int latestExploredCells = (int) latest[1];

        int remainingCells = totalCells - latestExploredCells;
        if (remainingCells <= 0) {
            result.estimatedRemainingTicks = 0;
            result.estimatedFinishTick = latestTick;
            result.confidence = n >= 10 ? "high" : (n >= 5 ? "medium" : "low");
            result.rSquared = computeRSquared(points, slope, intercept, meanY);
            return result;
        }

        long estimatedRemaining = Math.round((double) remainingCells / slope);
        result.estimatedRemainingTicks = estimatedRemaining;
        result.estimatedFinishTick = latestTick + estimatedRemaining;
        result.rSquared = computeRSquared(points, slope, intercept, meanY);

        if (n >= 10 && result.rSquared > 0.8) {
            result.confidence = "high";
        } else if (n >= 5) {
            result.confidence = "medium";
        } else {
            result.confidence = "low";
        }

        return result;
    }

    /** 计算线性回归的决定系数 R² */
    private static double computeRSquared(List<long[]> points, double slope,
                                          double intercept, double meanY) {
        double ssRes = 0;
        double ssTot = 0;
        for (long[] p : points) {
            double x = p[0];
            double y = p[1];
            double predicted = intercept + slope * x;
            ssRes += (y - predicted) * (y - predicted);
            ssTot += (y - meanY) * (y - meanY);
        }
        if (ssTot < 1e-9) {
            return 0;
        }
        return 1.0 - ssRes / ssTot;
    }

    /**
     * 预测结果
     */
    public static class PredictionResult {
        /** 预计剩余 tick 数，无法预测时为 -1 */
        public long estimatedRemainingTicks;
        /** 预计完成 tick，无法预测时为 -1 */
        public long estimatedFinishTick;
        /** 线性回归 R² */
        public double rSquared;
        /** 置信度：high / medium / low */
        public String confidence;
    }
}
