package com.blackboard.replay;

import com.alibaba.fastjson2.JSONObject;
import com.blackboard.api.Blackboard;
import com.blackboard.model.Position;
import com.blackboard.model.SimState;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 路径回放 HTTP 查询接口（端口 8084）
 */
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/replay")
public class ReplayHttpServer {

    private final Blackboard board;
    private final SqlReplayPersistence sqlPersistence;

    public ReplayHttpServer(Blackboard board, SqlReplayPersistence sqlPersistence) {
        this.board = board;
        this.sqlPersistence = sqlPersistence;
    }

    /**
     * 分页查询全部回放会话
     * GET /api/replay/sessions?page=0&size=20
     */
    @GetMapping("/sessions")
    public Map<String, Object> listSessions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return sqlPersistence.listSessions(page, size);
    }

    /**
     * 查询指定会话的全部快照
     * GET /api/replay/sessions/{sessionId}/snapshots?from=0&to=100
     */
    @GetMapping("/sessions/{sessionId}/snapshots")
    public Map<String, Object> listSnapshots(
            @PathVariable String sessionId,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to) {
        return sqlPersistence.listSnapshots(sessionId, from, to);
    }

    /**
     * 任意时刻状态还原
     * GET /api/replay/sessions/{sessionId}/state?tick=42
     */
    @GetMapping("/sessions/{sessionId}/state")
    public Map<String, Object> rebuildState(
            @PathVariable String sessionId,
            @RequestParam long tick) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sessionId", sessionId);
        response.put("targetTick", tick);

        Map<String, Object> snapshot = sqlPersistence.findNearestSnapshot(sessionId, tick);
        if (snapshot == null) {
            response.put("state", null);
            return response;
        }

        long snapshotTick = (long) snapshot.get("tick");
        String stateJson = (String) snapshot.get("stateJson");
        SimState state = JSONObject.parseObject(stateJson, SimState.class);

        List<Map<String, Object>> moves = sqlPersistence.listMoveEvents(sessionId, snapshotTick, tick);
        applyMoveEvents(state, moves);

        response.put("state", state);
        return response;
    }

    /**
     * 查询指定会话、指定车辆的历史轨迹
     * GET /api/replay/sessions/{sessionId}/trace/{carId}
     */
    @GetMapping("/sessions/{sessionId}/trace/{carId}")
    public Map<String, Object> getSessionTrace(
            @PathVariable String sessionId,
            @PathVariable String carId) {
        List<Map<String, Object>> events = sqlPersistence.listTraceEvents(sessionId, carId);
        List<Map<String, Object>> points = new ArrayList<>();

        for (Map<String, Object> event : events) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("tick", extractTickFromExtra((String) event.get("extraJson")));
            point.put("x", event.get("x"));
            point.put("y", event.get("y"));
            points.add(point);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("carId", carId);
        response.put("points", points);
        response.put("total", points.size());
        return response;
    }

    /**
     * 读取当前运行中的快照（Redis 实时回放）
     * GET /api/replay/current/snapshots
     */
    @GetMapping("/current/snapshots")
    public Map<String, Object> getCurrentSnapshots() {
        List<String> rawSnapshots = board.getAllSnapshots();
        List<JSONObject> snapshots = new ArrayList<>();

        for (String json : rawSnapshots) {
            try {
                snapshots.add(JSONObject.parseObject(json));
            } catch (Exception e) {
                System.err.println("[ReplayHttpServer] 快照解析失败: " + json);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("total", snapshots.size());
        response.put("snapshots", snapshots);
        return response;
    }

    /**
     * 读取当前运行中的车辆轨迹（Redis）
     * GET /api/replay/current/trace/{carId}
     */
    @GetMapping("/current/trace/{carId}")
    public Map<String, Object> getCurrentTrace(@PathVariable String carId) {
        List<String> rawTrace = board.getTrace(carId);
        List<Map<String, Object>> points = new ArrayList<>();

        for (String line : rawTrace) {
            Map<String, Object> point = parseTraceLine(line);
            if (point != null) {
                points.add(point);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("carId", carId);
        response.put("points", points);
        response.put("total", points.size());
        return response;
    }

    // ==================== 内部工具方法 ====================

    /**
     * 将 MOVE 事件依次应用到 SimState 中的车辆位置
     */
    private void applyMoveEvents(SimState state, List<Map<String, Object>> moves) {
        if (state.getCars() == null) {
            return;
        }
        for (Map<String, Object> move : moves) {
            String carId = (String) move.get("carId");
            int x = (int) move.get("x");
            int y = (int) move.get("y");

            SimState.CarInfo carInfo = state.getCars().get(carId);
            if (carInfo != null) {
                carInfo.setPosition(new Position(x, y));
            }
        }
    }

    /** 从 extra_json 中提取 tick 字段 */
    private long extractTickFromExtra(String extraJson) {
        if (extraJson == null || extraJson.isEmpty()) {
            return 0;
        }
        try {
            return JSONObject.parseObject(extraJson).getLongValue("tick");
        } catch (Exception e) {
            return 0;
        }
    }

    /** 解析 Redis 轨迹行 "tick,x,y" 格式 */
    private Map<String, Object> parseTraceLine(String line) {
        try {
            String[] parts = line.split(",");
            if (parts.length < 3) {
                return null;
            }
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("tick", Long.parseLong(parts[0].trim()));
            point.put("x", Integer.parseInt(parts[1].trim()));
            point.put("y", Integer.parseInt(parts[2].trim()));
            return point;
        } catch (Exception e) {
            System.err.println("[ReplayHttpServer] 轨迹行解析失败: " + line);
            return null;
        }
    }
}
