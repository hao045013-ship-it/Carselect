package com.blackboard.replay;

import com.alibaba.fastjson2.JSONObject;
import com.blackboard.api.Blackboard;
import com.blackboard.api.MessageQueue;
import com.blackboard.constant.MQKeys;
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
            case MQKeys.CMD_START -> handleStart(timestamp);
            case MQKeys.CMD_RESET -> handleReset(timestamp);
            case MQKeys.CMD_REFRESH_ALL -> handleRefreshAll(timestamp);
            case MQKeys.CMD_MOVED -> handleMoved(data, timestamp);
            default -> {
                // 其他命令忽略
            }
        }
    }

    /** START：创建新会话 */
    private void handleStart(long timestamp) {
        currentSessionId = UUID.randomUUID().toString();
        int mapWidth = board.getMapWidth();
        int mapHeight = board.getMapHeight();
        int carCount = board.getCarList().size(); // 用 getCarList() 更准确
        sqlPersistence.startSession(currentSessionId, timestamp, mapWidth, mapHeight, carCount);
    }

    /** RESET：结束当前会话 */
    private void handleReset(long timestamp) {
        if (currentSessionId != null) {
            sqlPersistence.endSession(currentSessionId, timestamp);
            currentSessionId = null;
        }
    }

    /** REFRESH_ALL：保存完整状态快照 */
    private void handleRefreshAll(long timestamp) {
        SimState simState = buildSimState();
        String stateJson = simState.toJson();
        long tick = simState.getTick();
        double coverage = simState.getExploredPercent() / 100.0;

        board.saveSnapshot(stateJson);
        board.saveCoverageHistory(tick, coverage);

        if (currentSessionId != null) {
            sqlPersistence.insertSnapshot(currentSessionId, timestamp, tick, coverage, stateJson);
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

        board.appendTrace(carId, tick, x, y);

        if (currentSessionId != null) {
            JSONObject extra = new JSONObject();
            extra.put("tick", tick);
            sqlPersistence.insertEventLog(
                    currentSessionId, timestamp, "MOVE", carId, x, y, extra.toJSONString());
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
