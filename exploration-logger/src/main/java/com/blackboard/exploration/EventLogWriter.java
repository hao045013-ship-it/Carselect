package com.blackboard.exploration;

import com.alibaba.fastjson2.JSONObject;
import com.blackboard.api.Blackboard;
import com.blackboard.api.MessageQueue;
import com.blackboard.constant.MQKeys;
import com.blackboard.constant.RedisKeys;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.blackboard.constant.RedisKeys.*;

/**
 * 探索事件日志写入器 —— 订阅 UpdateView 广播，将事件格式化后写入 Redis 日志
 */
public class EventLogWriter {

    private final Blackboard board;
    private final MessageQueue mq;
    private final SqlPersistence sqlPersistence;

    /** 当前 SQL 会话 ID，未开始时为 null */
    private String currentSessionId;

    /** 是否已写入会话结束时间（覆盖率到100%即为true，避免RESET覆盖） */
    private boolean sessionEnded = false;

    /** 记录每辆车上一位置，用于计算 newCells */
    private final Map<String, int[]> lastPositions = new ConcurrentHashMap<>();

    public EventLogWriter(Blackboard board, MessageQueue mq, SqlPersistence sqlPersistence) {
        this.board = board;
        this.mq = mq;
        this.sqlPersistence = sqlPersistence;
    }

    /**
     * 开始订阅 MQ 广播消息
     */
    public void start() {
        mq.subscribeUpdateView(message -> {
            try {
                handleMessage(message);
            } catch (Exception e) {
                // 解析失败时打印异常，不中断订阅循环
                System.err.println("[EventLogWriter] 日志解析失败: " + message);
                e.printStackTrace();
            }
        });
        System.out.println("[EventLogWriter] 已订阅 UpdateView 广播，开始记录探索日志");
    }

    /**
     * 解析 MQ 消息并写入对应格式的日志条目
     */
    private void handleMessage(String message) {
        JSONObject root = JSONObject.parseObject(message);
        String cmd = root.getString("cmd");
        long timestamp = root.getLongValue("timestamp");
        JSONObject data = root.getJSONObject("data");

        if (cmd == null) {
            return;
        }

        if (MQKeys.CMD_START.equals(cmd)) {
            currentSessionId = data == null ? null : data.getString("sessionId");
            sessionEnded = false;

            return;
        }

        if (MQKeys.CMD_RESET.equals(cmd)) {
            if (currentSessionId != null) {
                // 覆盖率到100%时已经end过，不再覆盖
                if (!sessionEnded) {
                    sqlPersistence.endSession(currentSessionId, timestamp);
                }
                currentSessionId = null;
            }
            sessionEnded = false;
            return;
        }

        if (data == null) {
            return;
        }

        String logEntry;
        switch (cmd) {
            case MQKeys.CMD_MOVED -> logEntry = buildMoveEntry(data, timestamp);
            case MQKeys.CMD_BLOCKED -> logEntry = buildBlockedEntry(data, timestamp);
            case MQKeys.CMD_ROUTE_DONE -> logEntry = buildRouteDoneEntry(data, timestamp);
            case MQKeys.CMD_ROUTE_PLANNED -> logEntry = buildRoutePlannedEntry(data, timestamp);
            case MQKeys.CMD_REFRESH_ALL -> logEntry = buildSnapshotEntry(data, timestamp);
            default -> {
                return;
            }
        }

        board.addLogEntry(logEntry);

        if (currentSessionId != null) {
            JSONObject entry = JSONObject.parseObject(logEntry);
            sqlPersistence.insertEventLog(
                    currentSessionId,
                    timestamp,
                    entry.getString("type"),
                    entry.getString("carId"),
                    entry.getInteger("x"),
                    entry.getInteger("y"),
                    entry.getJSONObject("extra").toJSONString());

            // 覆盖率到100%时立刻结束会话，不等RESET
            if (!sessionEnded && "SNAPSHOT".equals(entry.getString("type"))) {
                double coverage = board.getExploredPercent();
                if (coverage >= 100.0) {
                    sqlPersistence.endSession(currentSessionId, timestamp);
                    sessionEnded = true;
                }
            }
        }
    }

    /** MOVED → MOVE */
    private String buildMoveEntry(JSONObject data, long timestamp) {
        String carId = data.getString("carId");
        int x = data.getIntValue("x");
        int y = data.getIntValue("y");

        int newCells = computeNewCells(carId, x, y);
        lastPositions.put(carId, new int[]{x, y});

        JSONObject extra = new JSONObject();
        extra.put("newCells", newCells);

        return buildLogJson("MOVE", carId, timestamp, x, y, extra);
    }

    /** BLOCKED → BLOCKED */
    private String buildBlockedEntry(JSONObject data, long timestamp) {
        String carId = data.getString("carId");
        int x = data.getIntValue("x");
        int y = data.getIntValue("y");

        JSONObject extra = new JSONObject();
        extra.put("blockedX", data.getIntValue("blockedX"));
        extra.put("blockedY", data.getIntValue("blockedY"));

        return buildLogJson("BLOCKED", carId, timestamp, x, y, extra);
    }

    /** ROUTE_DONE → ROUTE_DONE */
    private String buildRouteDoneEntry(JSONObject data, long timestamp) {
        String carId = data.getString("carId");
        int x = data.getIntValue("x");
        int y = data.getIntValue("y");

        return buildLogJson("ROUTE_DONE", carId, timestamp, x, y, new JSONObject());
    }

    /** ROUTE_PLANNED → ROUTE_PLANNED */
    private String buildRoutePlannedEntry(JSONObject data, long timestamp) {
        String carId = data.getString("carId");

        JSONObject extra = new JSONObject();
        extra.put("routeFound", data.getBooleanValue("routeFound"));
        extra.put("routeLength", data.getIntValue("routeLength"));

        return buildLogJson("ROUTE_PLANNED", carId, timestamp, -1, -1, extra);
    }

    /** REFRESH_ALL → SNAPSHOT */
    private String buildSnapshotEntry(JSONObject data, long timestamp) {
        JSONObject extra = new JSONObject();
        extra.put("tick", data.getLongValue("tick"));
        extra.put("coverage", board.getExploredPercent());

        return buildLogJson("SNAPSHOT", null, timestamp, -1, -1, extra);
    }

    /**
     * 构建统一格式的日志 JSON 字符串
     */
    private String buildLogJson(String type, String carId, long ts, int x, int y, JSONObject extra) {
        JSONObject entry = new JSONObject();
        entry.put("type", type);
        entry.put("carId", carId);
        entry.put("ts", ts);
        entry.put("x", x);
        entry.put("y", y);
        entry.put("extra", extra);
        return entry.toJSONString();
    }

    /**
     * 根据视野半径计算本次移动新点亮的格子数
     */
    private int computeNewCells(String carId, int newX, int newY) {
        int radius = VISION_RANGE;
        Set<String> newVision = visionCells(newX, newY, radius);

        int[] last = lastPositions.get(carId);
        if (last == null) {
            return newVision.size();
        }

        Set<String> oldVision = visionCells(last[0], last[1], radius);
        newVision.removeAll(oldVision);
        return newVision.size();
    }

    /** 获取指定坐标视野范围内的所有格子坐标键 */
    private Set<String> visionCells(int x, int y, int radius) {
        Set<String> cells = new HashSet<>();
        for (int dr = -radius; dr <= radius; dr++) {
            for (int dc = -radius; dc <= radius; dc++) {
                int row = y + dr;
                int col = x + dc;
                if (row >= 0 && row < RedisKeys.DEFAULT_MAP_HEIGHT && col >= 0 && col < RedisKeys.DEFAULT_MAP_WIDTH) {
                    cells.add(row + "," + col);
                }
            }
        }
        return cells;
    }
}
