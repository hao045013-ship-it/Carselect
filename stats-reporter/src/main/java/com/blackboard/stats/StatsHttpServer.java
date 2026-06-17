package com.blackboard.stats;

import com.alibaba.fastjson2.JSONObject;
import com.blackboard.api.Blackboard;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统计分析 HTTP 查询接口（端口 8085）
 */
@CrossOrigin(origins = "*")
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

    /**
     * 多会话覆盖率曲线对比
     * GET /api/stats/sessions/compare?ids=id1,id2,id3
     */
    @GetMapping("/sessions/compare")
    public Map<String, Object> compareSessions(@RequestParam String ids) {
        Map<String, Object> response = new LinkedHashMap<>();

        if (ids == null || ids.isBlank()) {
            response.put("sessions", List.of());
            response.put("curves", Map.of());
            return response;
        }

        List<String> idList = new ArrayList<>(Arrays.asList(ids.split(",")));
        idList.removeIf(String::isBlank);
        if (idList.size() > 5) {
            idList = idList.subList(0, 5);
        }

        Map<String, List<Map<String, Object>>> curves = sqlPersistence.getCompareData(idList);

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        List<Map<String, Object>> sessions = new ArrayList<>();
        for (String sid : idList) {
            Map<String, Object> sessionInfo = new LinkedHashMap<>();
            sessionInfo.put("sessionId", sid);

            Map<String, Object> stats = sqlPersistence.getSessionStats(sid);
            if (!stats.isEmpty() && stats.get("updatedAt") != null) {
                long updatedAt = (long) stats.get("updatedAt");
                String label = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(updatedAt), ZoneId.systemDefault()).format(fmt);
                sessionInfo.put("label", label);
            } else {
                sessionInfo.put("label", sid.length() > 8 ? sid.substring(0, 8) : sid);
            }
            sessions.add(sessionInfo);
        }

        response.put("sessions", sessions);
        response.put("curves", curves);
        return response;
    }

    /**
     * 热力图访问数据
     * GET /api/stats/sessions/{sessionId}/heatmap
     */
    @GetMapping("/sessions/{sessionId}/heatmap")
    public Map<String, Object> getHeatmap(@PathVariable String sessionId) {
        return sqlPersistence.getHeatmapData(sessionId);
    }
}
