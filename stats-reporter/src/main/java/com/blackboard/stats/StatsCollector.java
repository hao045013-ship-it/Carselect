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

    /** 是否已经写入最终时长（覆盖率到100%时即为true，避免RESET覆盖） */
    private boolean sessionFinalized = false;

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
        JSONObject data = root.getJSONObject("data");

        if (cmd == null) {
            return;
        }

        switch (cmd) {
            case MQKeys.CMD_START -> handleStart(timestamp, data);
            case MQKeys.CMD_REFRESH_ALL -> handleRefreshAll(timestamp, data);
            case MQKeys.CMD_RESET -> handleReset(timestamp);
            default -> {
                // 其他命令忽略
            }
        }
    }

    /** START：从消息体直接取 sessionId（避免查库竞态），重置本地缓存 */
    private void handleStart(long timestamp, JSONObject data) {
        currentSessionId = (data != null) ? data.getString("sessionId") : null;
        sessionStartTime = timestamp;
        sessionFinalized = false;
        lastStepSnapshot.clear();
        predictionEngine.reset();
    }

    /** REFRESH_ALL：汇总统计、预测、写黑板与数据库 */
    private void handleRefreshAll(long timestamp, JSONObject data) {
        // 兜底：如果错过了START消息，从REFRESH_ALL消息里取sessionId
        if (currentSessionId == null && data != null) {
            String sid = data.getString("sessionId");
            if (sid != null && !sid.isBlank()) {
                currentSessionId = sid;
                sessionStartTime = timestamp;
            }
        }

        // 验证消息中的sessionId与当前会话一致（切到下一轮仿真时跳过旧消息）
        if (currentSessionId != null && data != null) {
            String msgSid = data.getString("sessionId");
            if (msgSid != null && !msgSid.isBlank() && !msgSid.equals(currentSessionId)) {
                return; // 旧会话的残留消息，忽略
            }
        }

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

        // 导航效率：累积已探索格子 / 累积导航次数
        double avgNavEfficiency = (totalNavCount > 0) ? (double) exploredCells / totalNavCount : 0.0;

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

            // 覆盖率到达100%时立刻写入时长，不等RESET
            if (coverage >= 1.0 && !sessionFinalized) {
                long durationMs = timestamp - sessionStartTime;
                sqlPersistence.finalizeSessionStats(currentSessionId, durationMs);
                sessionFinalized = true;
            }
        }
    }

    /** RESET：兜底写入最终时长（覆盖率未到100%就重置的场景），清空会话与预测数据 */
    private void handleReset(long timestamp) {
        if (currentSessionId != null) {
            // 如果覆盖率已到100%则时长已经写入，不再覆盖
            if (!sessionFinalized) {
                long durationMs = timestamp - sessionStartTime;
                sqlPersistence.finalizeSessionStats(currentSessionId, durationMs);
            }
            currentSessionId = null;
        }
        sessionFinalized = false;
        predictionEngine.reset();
        lastStepSnapshot.clear();
    }
}
