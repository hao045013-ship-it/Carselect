package com.blackboard.stats;

import com.alibaba.fastjson2.JSONObject;
import com.blackboard.api.Blackboard;
import com.blackboard.api.MessageQueue;
import com.blackboard.constant.MQKeys;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 统计收集器 —— 订阅 UpdateView 广播，汇总统计数据并写入黑板与 SQL Server
 */
public class StatsCollector {

    private final Blackboard board;
    private final MessageQueue mq;
    private final SqlStatsPersistence sqlPersistence;
    private final PredictionEngine predictionEngine;

    /** 当前关联的 SQL 会话 ID */
    private String currentSessionId;

    /** 当前会话开始时间戳（ms） */
    private long sessionStartTime;

    /** 每辆车上次记录的步数快照，START 时重置 */
    private final Map<String, Integer> lastStepSnapshot = new HashMap<>();

    /** 上次记录的已探索格子数，用于计算导航效率增量 */
    private int lastExploredCells = 0;
    /** 上次记录的总导航次数，用于计算导航效率增量 */
    private int lastTotalNavCount = 0;

    public StatsCollector(Blackboard board, MessageQueue mq,
                          SqlStatsPersistence sqlPersistence,
                          PredictionEngine predictionEngine) {
        this.board = board;
        this.mq = mq;
        this.sqlPersistence = sqlPersistence;
        this.predictionEngine = predictionEngine;
    }

    /**
     * 开始订阅 MQ 广播消息
     */
    public void start() {
        mq.subscribeUpdateView(message -> {
            try {
                handleMessage(message);
            } catch (Exception e) {
                System.err.println("[StatsCollector] 消息处理失败: " + message);
                e.printStackTrace();
            }
        });
        System.out.println("[StatsCollector] 已订阅 UpdateView 广播，开始收集统计数据");
    }

    private void handleMessage(String message) {
        JSONObject root = JSONObject.parseObject(message);
        String cmd = root.getString("cmd");
        long timestamp = root.getLongValue("timestamp");

        if (cmd == null) {
            return;
        }

        switch (cmd) {
            case MQKeys.CMD_START -> handleStart(timestamp);
            case MQKeys.CMD_REFRESH_ALL -> handleRefreshAll(timestamp);
            case MQKeys.CMD_RESET -> handleReset(timestamp);
            default -> {
                // 其他命令忽略
            }
        }
    }

    /** START：关联最新会话，重置本地缓存 */
    private void handleStart(long timestamp) {
        currentSessionId = sqlPersistence.getLatestSessionId();
        sessionStartTime = timestamp;
        lastStepSnapshot.clear();
        lastExploredCells = 0;
        lastTotalNavCount = 0;
        predictionEngine.reset();
    }

    /** REFRESH_ALL：汇总统计、预测、写黑板与数据库 */
    private void handleRefreshAll(long timestamp) {
        double exploredPercent = board.getExploredPercent();
        long tick = board.getCurrentTick();
        int mapWidth = board.getMapWidth();
        int mapHeight = board.getMapHeight();
        int totalCells = mapWidth * mapHeight;
        int exploredCells = (int) (exploredPercent / 100.0 * totalCells);
        double coverage = exploredPercent / 100.0;

        List<String> carIds = board.getCarList();
        JSONObject carsJson = new JSONObject();
        int totalMoves = 0;
        int totalBlocked = 0;
        int totalNavCount = 0;

        for (String carId : carIds) {
            int steps = board.getSteps(carId);
            int blocked = board.getBlockedCount(carId);
            int navCount = board.getRoutePlanCount(carId);

            JSONObject carStats = new JSONObject();
            carStats.put("steps", steps);
            carStats.put("blocked", blocked);
            carStats.put("navCount", navCount);
            carsJson.put(carId, carStats);

            totalMoves += steps;
            totalBlocked += blocked;
            totalNavCount += navCount;
            lastStepSnapshot.put(carId, steps);
        }

        predictionEngine.addPoint(tick, exploredCells);
        PredictionEngine.PredictionResult prediction = predictionEngine.predict(totalCells);

        // 导航效率：本次增量的已探索格子 / 本次增量的导航次数
        int deltaExplored = exploredCells - lastExploredCells;
        int deltaNav = totalNavCount - lastTotalNavCount;
        double avgNavEfficiency = (deltaNav > 0) ? (double) deltaExplored / deltaNav : 0.0;
        lastExploredCells = exploredCells;
        lastTotalNavCount = totalNavCount;

        JSONObject predictionJson = new JSONObject();
        predictionJson.put("estimatedRemainingTicks", prediction.estimatedRemainingTicks);
        predictionJson.put("estimatedFinishTick", prediction.estimatedFinishTick);
        predictionJson.put("confidence", prediction.confidence);

        JSONObject report = new JSONObject();
        report.put("tick", tick);
        report.put("exploredPercent", exploredPercent);
        report.put("exploredCells", exploredCells);
        report.put("totalCells", totalCells);
        report.put("coverage", coverage);
        report.put("avgNavEfficiency", avgNavEfficiency);
        report.put("cars", carsJson);
        report.put("prediction", predictionJson);
        report.put("updatedAt", timestamp);

        board.setStatsReport(report.toJSONString());

        if (currentSessionId != null) {
            sqlPersistence.insertCoverageCurve(
                    currentSessionId, timestamp, tick, coverage, exploredCells);
            sqlPersistence.upsertSessionStats(
                    currentSessionId,
                    totalCells,
                    exploredCells,
                    coverage,
                    totalMoves,
                    totalBlocked,
                    totalNavCount,
                    avgNavEfficiency,
                    carsJson.toJSONString(),
                    timestamp);
        }
    }

    /** RESET：写入最终时长，清空会话与预测数据 */
    private void handleReset(long timestamp) {
        if (currentSessionId != null) {
            long durationMs = timestamp - sessionStartTime;
            sqlPersistence.finalizeSessionStats(currentSessionId, durationMs);
            currentSessionId = null;
        }
        predictionEngine.reset();
        lastStepSnapshot.clear();
    }
}
