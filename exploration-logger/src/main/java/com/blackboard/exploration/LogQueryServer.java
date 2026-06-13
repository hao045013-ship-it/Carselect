package com.blackboard.exploration;

import com.alibaba.fastjson2.JSONObject;
import com.blackboard.api.Blackboard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 探索日志 HTTP 查询接口（端口 8083）
 */
@RestController
@RequestMapping("/api/logs")
public class LogQueryServer {

    /** 单次最多读取的日志条数（与黑板保留上限对齐） */
    private static final int MAX_LOGS = 5000;

    private final Blackboard board;

    public LogQueryServer(Blackboard board) {
        this.board = board;
    }

    /**
     * 获取最新 N 条日志
     * GET /api/logs?count=100
     */
    @GetMapping
    public Map<String, Object> getLogs(@RequestParam(defaultValue = "100") int count) {
        List<JSONObject> entries = loadEntries(Math.min(count, MAX_LOGS));
        return buildResponse(entries);
    }

    /**
     * 按车辆 ID 过滤日志
     * GET /api/logs/car/{carId}?count=50
     */
    @GetMapping("/car/{carId}")
    public Map<String, Object> getLogsByCar(
            @PathVariable String carId,
            @RequestParam(defaultValue = "50") int count) {
        List<JSONObject> entries = filterEntries(
                e -> carId.equals(e.getString("carId")),
                Math.min(count, MAX_LOGS));
        return buildResponse(entries);
    }

    /**
     * 按事件类型过滤日志
     * GET /api/logs/type/{type}?count=50
     */
    @GetMapping("/type/{type}")
    public Map<String, Object> getLogsByType(
            @PathVariable String type,
            @RequestParam(defaultValue = "50") int count) {
        List<JSONObject> entries = filterEntries(
                e -> type.equalsIgnoreCase(e.getString("type")),
                Math.min(count, MAX_LOGS));
        return buildResponse(entries);
    }

    /**
     * 按时间戳范围查询日志
     * GET /api/logs/range?from=1718000000000&to=1718003600000
     */
    @GetMapping("/range")
    public Map<String, Object> getLogsByRange(
            @RequestParam long from,
            @RequestParam long to) {
        List<JSONObject> entries = filterEntries(
                e -> {
                    long ts = e.getLongValue("ts");
                    return ts >= from && ts <= to;
                },
                MAX_LOGS);
        return buildResponse(entries);
    }

    /**
     * 获取指定车辆的探索统计
     * GET /api/logs/stats/car/{carId}
     */
    @GetMapping("/stats/car/{carId}")
    public Map<String, Object> getCarStats(@PathVariable String carId) {
        List<JSONObject> all = filterEntries(e -> carId.equals(e.getString("carId")), MAX_LOGS);

        int totalMoves = 0;
        int blockedCount = 0;
        int routesDone = 0;

        for (JSONObject entry : all) {
            String type = entry.getString("type");
            if ("MOVE".equals(type)) {
                totalMoves++;
            } else if ("BLOCKED".equals(type)) {
                blockedCount++;
            } else if ("ROUTE_DONE".equals(type)) {
                routesDone++;
            }
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("carId", carId);
        stats.put("totalMoves", totalMoves);
        stats.put("blockedCount", blockedCount);
        stats.put("routesDone", routesDone);
        return stats;
    }

    // ==================== 内部工具方法 ====================

    /** 从 Redis 加载并解析最新 N 条日志 */
    private List<JSONObject> loadEntries(int count) {
        List<String> raw = board.getLogs(count);
        return parseAll(raw);
    }

    /** 从全量日志中按条件过滤，最多返回 limit 条 */
    private List<JSONObject> filterEntries(Predicate<JSONObject> predicate, int limit) {
        List<String> raw = board.getLogs(MAX_LOGS);
        List<JSONObject> result = new ArrayList<>();
        for (String line : raw) {
            JSONObject entry = safeParse(line);
            if (entry != null && predicate.test(entry)) {
                result.add(entry);
                if (result.size() >= limit) {
                    break;
                }
            }
        }
        return result;
    }

    /** 将原始 JSON 字符串列表解析为 JSONObject 列表 */
    private List<JSONObject> parseAll(List<String> raw) {
        List<JSONObject> result = new ArrayList<>(raw.size());
        for (String line : raw) {
            JSONObject entry = safeParse(line);
            if (entry != null) {
                result.add(entry);
            }
        }
        return result;
    }

    /** 安全解析单条日志，失败时返回 null */
    private JSONObject safeParse(String line) {
        try {
            return JSONObject.parseObject(line);
        } catch (Exception e) {
            System.err.println("[LogQueryServer] 日志条目解析失败: " + line);
            return null;
        }
    }

    /** 构建统一响应格式 */
    private Map<String, Object> buildResponse(List<JSONObject> entries) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("total", entries.size());
        response.put("entries", entries);
        return response;
    }
}
