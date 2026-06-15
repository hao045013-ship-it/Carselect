package com.blackboard.stats;

import com.alibaba.fastjson2.JSONObject;
import com.blackboard.api.Blackboard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统计分析 HTTP 查询接口（端口 8085）
 */
@RestController
@RequestMapping("/api/stats")
public class StatsHttpServer {

    private static final int PREDICTION_WINDOW = 20;

    private final Blackboard board;
    private final SqlStatsPersistence sqlPersistence;

    public StatsHttpServer(Blackboard board, SqlStatsPersistence sqlPersistence) {
        this.board = board;
        this.sqlPersistence = sqlPersistence;
    }

    /**
     * 读取黑板上的实时统计报告
     * GET /api/stats/current
     */
    @GetMapping("/current")
    public Map<String, Object> getCurrentStats() {
        String report = board.getStatsReport();
        if (report == null || report.isEmpty()) {
            return Map.of();
        }
        try {
            return JSONObject.parseObject(report);
        } catch (Exception e) {
            System.err.println("[StatsHttpServer] 统计报告解析失败: " + report);
            e.printStackTrace();
            return Map.of();
        }
    }

    /**
     * 查询指定会话的统计概览
     * GET /api/stats/sessions/{sessionId}/overview
     */
    @GetMapping("/sessions/{sessionId}/overview")
    public Map<String, Object> getSessionOverview(@PathVariable String sessionId) {
        return sqlPersistence.getSessionStats(sessionId);
    }

    /**
     * 查询指定会话的覆盖率曲线（折线图数据）
     * GET /api/stats/sessions/{sessionId}/coverage-curve
     */
    @GetMapping("/sessions/{sessionId}/coverage-curve")
    public Map<String, Object> getCoverageCurve(@PathVariable String sessionId) {
        return sqlPersistence.getCoverageCurve(sessionId);
    }

    /**
     * 查询各车贡献数据（柱状图数据）
     * GET /api/stats/sessions/{sessionId}/car-contribution
     */
    @GetMapping("/sessions/{sessionId}/car-contribution")
    public Map<String, Object> getCarContribution(@PathVariable String sessionId) {
        List<Map<String, Object>> cars = sqlPersistence.getCarContribution(sessionId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sessionId", sessionId);
        response.put("cars", cars);
        return response;
    }

    /**
     * 基于历史覆盖率曲线预测完成时间
     * GET /api/stats/sessions/{sessionId}/predict
     */
    @GetMapping("/sessions/{sessionId}/predict")
    public Map<String, Object> predictCompletion(@PathVariable String sessionId) {
        Map<String, Object> sessionStats = sqlPersistence.getSessionStats(sessionId);

        int totalCells;
        int exploredCells;
        double currentCoverage;

        if (!sessionStats.isEmpty()) {
            totalCells = (int) sessionStats.get("totalCells");
            exploredCells = (int) sessionStats.get("exploredCells");
            currentCoverage = (double) sessionStats.get("coverageRate");
        } else {
            totalCells = sqlPersistence.getSessionTotalCells(sessionId);
            exploredCells = 0;
            currentCoverage = 0;
        }

        List<long[]> recentPoints = sqlPersistence.getRecentCoveragePoints(sessionId, PREDICTION_WINDOW);
        if (!recentPoints.isEmpty()) {
            // Integration note: JDK 17 compatible replacement for List.getLast().
            long[] latest = recentPoints.get(recentPoints.size() - 1);
            exploredCells = (int) latest[1];
            currentCoverage = totalCells > 0 ? (double) exploredCells / totalCells : 0;
        }

        PredictionEngine.PredictionResult prediction =
                PredictionEngine.predictFromPoints(recentPoints, totalCells);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("currentCoverage", currentCoverage);
        response.put("exploredCells", exploredCells);
        response.put("totalCells", totalCells);
        response.put("remainingCells", Math.max(0, totalCells - exploredCells));
        response.put("estimatedRemainingTicks", prediction.estimatedRemainingTicks);
        response.put("estimatedFinishTick", prediction.estimatedFinishTick);
        response.put("confidence", prediction.confidence);
        return response;
    }

    /**
     * 分页查询历史会话统计列表
     * GET /api/stats/sessions?page=0&size=20
     */
    @GetMapping("/sessions")
    public Map<String, Object> listSessions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return sqlPersistence.listSessions(page, size);
    }
}
