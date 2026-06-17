package com.blackboard.replay;

import com.alibaba.fastjson2.JSONObject;
import com.blackboard.api.Blackboard;
import com.blackboard.api.MessageQueue;
import com.blackboard.constant.MQKeys;
import com.blackboard.constant.RedisKeys;
import com.blackboard.model.Position;
import com.blackboard.model.SimState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 快照与轨迹记录器 —— 订阅 UpdateView 广播，写入 Redis 与 SQL Server
 */
public class SnapshotRecorder {

    private final Blackboard board;
    private final MessageQueue mq;
    private final SqlReplayPersistence sqlPersistence;

    /** 当前 SQL 会话 ID，未开始时为 null */
    private String currentSessionId;
    private long lastSqlSnapshotTick = Long.MIN_VALUE;
    private long sessionStartBoardTick = 0L;

    public SnapshotRecorder(Blackboard board, MessageQueue mq, SqlReplayPersistence sqlPersistence) {
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
                System.err.println("[SnapshotRecorder] 消息处理失败: " + message);
                e.printStackTrace();
            }
        });
        System.out.println("[SnapshotRecorder] 已订阅 UpdateView 广播，开始记录快照与轨迹");
    }

    /**
     * 根据 cmd 分发处理逻辑
     */
    private void handleMessage(String message) {
        JSONObject root = JSONObject.parseObject(message);
        String cmd = root.getString("cmd");
        long timestamp = root.getLongValue("timestamp");
        JSONObject data = root.getJSONObject("data");

        if (cmd == null) {
            return;
        }

        switch (cmd) {
            case MQKeys.CMD_START -> handleStart(data,timestamp);
            case MQKeys.CMD_RESET -> handleReset(timestamp);
            case MQKeys.CMD_REFRESH_ALL -> handleRefreshAll(data, timestamp);
            case MQKeys.CMD_MOVED -> handleMoved(data, timestamp);
            case MQKeys.CMD_TASK_FINISHED -> handleReset(timestamp);
            default -> {
                // 其他命令忽略
            }
        }
    }

    /** START：创建新会话 */
    private void handleStart(JSONObject data,long timestamp) {
        // 优先使用 Controller 广播的 sessionId
        String sessionIdFromEvent = data == null ? null : data.getString("sessionId");
        if (sessionIdFromEvent != null && !sessionIdFromEvent.isBlank()) {
            currentSessionId = sessionIdFromEvent;
        } else {
            currentSessionId = UUID.randomUUID().toString(); // fallback
        }

        lastSqlSnapshotTick = Long.MIN_VALUE;
        sessionStartBoardTick = board.getCurrentTick();
        SimState initialState = null;
        String initialStateJson = data == null ? null : data.getString("initialStateJson");
        if (initialStateJson != null && !initialStateJson.isBlank()) {
            initialState = JSONObject.parseObject(initialStateJson, SimState.class);
        }

        if (initialState == null) {
            initialState = buildSimState();
        }

        initialState.setTick(0L);

        int mapWidth = board.getMapWidth();
        int mapHeight = board.getMapHeight();
        int carCount = board.getCarList().size();
        sqlPersistence.startSession(currentSessionId, timestamp, mapWidth, mapHeight, carCount);
        //SimState initialState = buildSimState();
        // normalizeInitialState(initialState);   // 已删除
        String stateJson = initialState.toJson();
        board.saveSnapshot(stateJson);
        persistSqlSnapshot(timestamp, 0L, initialState);
    }

    /** RESET：结束当前会话 */
    private void handleReset(long timestamp) {
        if (currentSessionId != null) {
            sqlPersistence.endSession(currentSessionId, timestamp);
            currentSessionId = null;
        }
    }

    /** REFRESH_ALL：保存完整状态快照 */
    private void handleRefreshAll(JSONObject data, long timestamp) {
        SimState simState = null;
        String stateJsonFromEvent = data == null ? null : data.getString("stateJson");
        if (stateJsonFromEvent != null && !stateJsonFromEvent.isBlank()) {
            simState = JSONObject.parseObject(stateJsonFromEvent, SimState.class);
        }
        if (simState == null) {
            simState = buildSimState();
        }

        long boardTick = data != null && data.containsKey("tick")
                ? data.getLongValue("tick")
                : simState.getTick();
        long tick = toReplayTick(boardTick);
        simState.setTick(tick);

        if (currentSessionId != null && tick <= lastSqlSnapshotTick) {
            return;
        }

        String stateJson = simState.toJson();
        double coverage = simState.getExploredPercent() / 100.0;

        board.saveSnapshot(stateJson);
        board.saveCoverageHistory(tick, coverage);

        if (currentSessionId != null) {
            persistSqlSnapshot(timestamp, tick, simState);
        }
    }

    /** MOVED：记录车辆轨迹 */
    private void handleMoved(JSONObject data, long timestamp) {
        if (data == null) {
            return;
        }
        String carId = data.getString("carId");
        int x = data.getIntValue("x");
        int y = data.getIntValue("y");
        long tick = board.getCurrentTick();
        long replayTick = currentSessionId == null ? tick : toReplayTick(tick);

        board.appendTrace(carId, tick, x, y);

        if (currentSessionId != null) {
            JSONObject extra = new JSONObject();
            extra.put("tick", replayTick);
            sqlPersistence.insertEventLog(
                    currentSessionId, timestamp, "MOVE", carId, x, y, extra.toJSONString());
        }
    }

    private long toReplayTick(long boardTick) {
        return Math.max(0L, boardTick - sessionStartBoardTick);
    }

    private void persistSqlSnapshot(long timestamp, long tick, SimState simState) {
        if (currentSessionId == null || tick == lastSqlSnapshotTick) {
            return;
        }
        double coverage = simState.getExploredPercent() / 100.0;
        sqlPersistence.insertSnapshot(currentSessionId, timestamp, tick, coverage, simState.toJson());
        lastSqlSnapshotTick = tick;
    }

    private void normalizeInitialState(SimState state) {
        int width = state.getMapWidth();
        int height = state.getMapHeight();
        boolean[] initialMapView = new boolean[Math.max(0, width * height)];
        boolean[] initialDynamicBlock = new boolean[Math.max(0, width * height)];

        Map<String, SimState.CarInfo> cars = state.getCars();
        if (cars != null) {
            for (String carId : cars.keySet()) {
                SimState.CarInfo car = cars.get(carId);
                Position start = findInitialPosition(carId, car.getPosition());
                car.setPosition(start);
                car.setTarget(null);
                car.setRouteList(new ArrayList<>());
                car.setStatus("IDLE");
                car.setStepsWalked(0);

                if (start != null) {
                    markInitialVision(initialMapView, width, height, start);
                    int idx = start.getY() * width + start.getX();
                    if (idx >= 0 && idx < initialDynamicBlock.length) {
                        initialDynamicBlock[idx] = true;
                    }
                }
            }
        }

        state.setMapView(initialMapView);
        state.setDynamicBlock(initialDynamicBlock);
        int explored = 0;
        for (boolean cell : initialMapView) {
            if (cell) {
                explored++;
            }
        }
        state.setExploredPercent(initialMapView.length == 0 ? 0.0 : explored * 100.0 / initialMapView.length);
    }

    private Position findInitialPosition(String carId, Position fallback) {
        long bestTick = Long.MAX_VALUE;
        Position best = null;
        for (String trace : board.getTrace(carId)) {
            String[] parts = trace.split(",");
            if (parts.length < 3) {
                continue;
            }
            try {
                long tick = Long.parseLong(parts[0].trim());
                int x = Integer.parseInt(parts[1].trim());
                int y = Integer.parseInt(parts[2].trim());
                if (tick < bestTick) {
                    bestTick = tick;
                    best = new Position(x, y);
                }
            } catch (NumberFormatException ignored) {
                // Ignore malformed trace rows.
            }
        }
        return best == null ? fallback : best;
    }

    private void markInitialVision(boolean[] mapView, int width, int height, Position center) {
        for (int dy = -RedisKeys.VISION_RANGE; dy <= RedisKeys.VISION_RANGE; dy++) {
            for (int dx = -RedisKeys.VISION_RANGE; dx <= RedisKeys.VISION_RANGE; dx++) {
                int x = center.getX() + dx;
                int y = center.getY() + dy;
                if (x >= 0 && x < width && y >= 0 && y < height) {
                    mapView[y * width + x] = true;
                }
            }
        }
    }

    /**
     * 从黑板读取完整状态，构建 SimState 对象
     */
    SimState buildSimState() {
        SimState state = new SimState();
        state.setMapWidth(board.getMapWidth());
        state.setMapHeight(board.getMapHeight());
        state.setMapView(board.getFullMapView());
        state.setStaticBlock(board.getFullStaticBlock());
        state.setDynamicBlock(board.getFullDynamicBlock());
        state.setExploredPercent(board.getExploredPercent());
        state.setTick(board.getCurrentTick());
        Map<String, String> config = board.getTaskConfig();
        if (config != null) {
            state.setStatus(config.get("taskStatus"));
        }
        state.setStatsReport(board.getStatsReport());
        state.setCoverageHistory(board.getCoverageHistory());
        state.setCars(buildCarInfoMap());
        return state;
    }

    /** 遍历车辆列表，读取每辆车的 position/target/status/steps/routeList */
    private Map<String, SimState.CarInfo> buildCarInfoMap() {
        Map<String, SimState.CarInfo> cars = new HashMap<>();
        for (String carId : board.getCarList()) {
            SimState.CarInfo info = new SimState.CarInfo();
            info.setCarId(carId);

            Map<String, String> posMap = board.getPosition(carId);
            if (posMap != null && posMap.containsKey("x")) {
                info.setPosition(new Position(
                        Integer.parseInt(posMap.get("x")),
                        Integer.parseInt(posMap.get("y"))));
            }

            Map<String, String> targetMap = board.getTarget(carId);
            if (targetMap != null && targetMap.containsKey("x")) {
                info.setTarget(new Position(
                        Integer.parseInt(targetMap.get("x")),
                        Integer.parseInt(targetMap.get("y"))));
            }

            info.setStatus(board.getStatus(carId));
            info.setStepsWalked(board.getSteps(carId));

            List<Position> routePositions = new ArrayList<>();
            for (String routeJson : board.getRouteList(carId)) {
                routePositions.add(Position.fromJson(routeJson));
            }
            info.setRouteList(routePositions);

            cars.put(carId, info);
        }
        return cars;
    }
}
