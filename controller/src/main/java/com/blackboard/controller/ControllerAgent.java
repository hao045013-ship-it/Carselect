package com.blackboard.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.blackboard.api.Blackboard;
import com.blackboard.api.MessageQueue;
import com.blackboard.constant.MQKeys;
import com.blackboard.constant.RedisKeys;
import com.blackboard.model.CarStatus;
import com.blackboard.model.TaskStatus;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

public class ControllerAgent {

    private final Blackboard board;
    private final MessageQueue mq;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

    private volatile boolean started = false;

    public ControllerAgent(Blackboard board, MessageQueue mq) {
        this.board = board;
        this.mq = mq;
    }

    public void start() {
        mq.subscribeController(this::handleMessage);

        long interval = getTickInterval();
        scheduler.scheduleAtFixedRate(
                this::safeTick,
                interval,
                interval,
                TimeUnit.MILLISECONDS
        );

        started = true;
        System.out.println("ControllerAgent started, tickInterval=" + interval + "ms");
    }

    private long getTickInterval() {
        Map<String, String> config = board.getTaskConfig();
        if (config == null) {
            return 500L;
        }

        String val = config.get("tickInterval");
        if (val == null || val.isBlank()) {
            return 500L;
        }

        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            return 500L;
        }
    }

    private void safeTick() {
        try {
            tick();
        } catch (Exception e) {
            e.printStackTrace();
            board.addLogEntry("ERROR: Controller tick failed: " + e.getMessage());
        }
    }

    private void handleMessage(String messageJson) {
        try {
            JSONObject msg = JSON.parseObject(messageJson);
            String cmd = msg.getString("cmd");
            JSONObject data = getDataAsObject(msg);

            if (cmd == null) {
                return;
            }

            switch (cmd) {
                // ===== 前端命令 =====
                case MQKeys.CMD_SET_CONFIG:
                    handleSetConfig(data);
                    break;

                case MQKeys.CMD_FORWARD_RESET:
                case MQKeys.CMD_RESET:
                    handleReset();
                    break;

                case MQKeys.CMD_START:
                    handleStart();
                    break;

                case MQKeys.CMD_PAUSE:
                    handlePause();
                    break;

                case MQKeys.CMD_RESUME:
                    handleResume();
                    break;

                case MQKeys.CMD_SET_OBSTACLE:
                case MQKeys.CMD_RANDOM_OBSTACLE:
                case MQKeys.CMD_CLEAR_OBSTACLE:
                    forwardToObstacleManager(cmd, data);
                    break;

                case MQKeys.CMD_ADD_CAR:
                    handleAddCar(data);
                    break;

                case MQKeys.CMD_LOAD_MAP_FILE:
                    forwardToTaskConfigurator(cmd, data);
                    break;

                // ===== 知识源反馈 =====
                case MQKeys.CMD_TASK_READY:
                    handleTaskReady(data);
                    break;

                case MQKeys.CMD_TARGET_ASSIGNED:
                    handleTargetAssigned(data);
                    break;

                case MQKeys.CMD_ROUTE_PLANNED:
                    handleRoutePlanned(data);
                    break;

                case MQKeys.CMD_MOVED:
                    handleMoved(data);
                    break;

                case MQKeys.CMD_BLOCKED:
                    handleBlocked(data);
                    break;

                case MQKeys.CMD_ROUTE_DONE:
                    handleRouteDone(data);
                    break;

                default:
                    board.addLogEntry("WARN: unknown command: " + cmd);
                    break;
            }

        } catch (Exception e) {
            e.printStackTrace();
            board.addLogEntry("ERROR: Controller handleMessage failed: " + e.getMessage());
        }
    }

    /**
     * 兼容两种 data：
     * 1. data 是 JSON 对象
     * 2. data 是字符串形式的 JSON
     */
    private JSONObject getDataAsObject(JSONObject msg) {
        Object raw = msg.get("data");

        if (raw == null) {
            return new JSONObject();
        }

        if (raw instanceof JSONObject) {
            return (JSONObject) raw;
        }

        if (raw instanceof String) {
            String str = (String) raw;
            if (str.isBlank()) {
                return new JSONObject();
            }
            return JSON.parseObject(str);
        }

        return JSON.parseObject(JSON.toJSONString(raw));
    }

    // =========================================================
    // 前端命令处理
    // =========================================================

    private void handleSetConfig(JSONObject data) {
        Map<String, Object> map = jsonObjectToMap(data);

        mq.sendToQueue(
                MQKeys.TASK_CONFIG_CMD,
                MQKeys.CMD_FORWARD_CONFIG,
                map
        );

        board.addLogEntry("INFO: Controller forward SET_CONFIG to TaskConfigurator");
    }

    private void handleReset() {
        mq.sendToQueue(
                MQKeys.TASK_CONFIG_CMD,
                MQKeys.CMD_FORWARD_RESET,
                Collections.emptyMap()
        );

        board.addLogEntry("INFO: Controller forward RESET to TaskConfigurator");
    }

    private void handleStart() {
        Map<String, String> config = board.getTaskConfig();
        if (config == null) {
            config = new HashMap<>();
        }

        config.put("taskStatus", TaskStatus.RUNNING.name());
        board.setTaskConfig(config);

        board.addLogEntry("INFO: task started");
        mq.broadcastRefreshAll(board.getCurrentTick());
    }

    private void handlePause() {
        Map<String, String> config = board.getTaskConfig();
        if (config == null) {
            config = new HashMap<>();
        }

        config.put("taskStatus", TaskStatus.PAUSED.name());
        board.setTaskConfig(config);

        board.addLogEntry("INFO: task paused");
        mq.broadcastRefreshAll(board.getCurrentTick());
    }

    private void handleResume() {
        Map<String, String> config = board.getTaskConfig();
        if (config == null) {
            config = new HashMap<>();
        }

        config.put("taskStatus", TaskStatus.RUNNING.name());
        board.setTaskConfig(config);

        board.addLogEntry("INFO: task resumed");
        mq.broadcastRefreshAll(board.getCurrentTick());
    }

    private void forwardToObstacleManager(String cmd, JSONObject data) {
        mq.sendToQueue(
                MQKeys.OBSTACLE_CMD,
                cmd,
                jsonObjectToMap(data)
        );

        board.addLogEntry("INFO: Controller forward obstacle command: " + cmd);
    }

    private void forwardToTaskConfigurator(String cmd, JSONObject data) {
        mq.sendToQueue(
                MQKeys.TASK_CONFIG_CMD,
                cmd,
                jsonObjectToMap(data)
        );

        board.addLogEntry("INFO: Controller forward task config command: " + cmd);
    }

    private void handleAddCar(JSONObject data) {
        String carId = data.getString("carId");
        int x = data.getIntValue("x");
        int y = data.getIntValue("y");

        if (carId == null || carId.isBlank()) {
            board.addLogEntry("WARN: ADD_CAR failed, carId empty");
            return;
        }

        if (board.carExists(carId)) {
            board.addLogEntry("WARN: ADD_CAR failed, car exists: " + carId);
            return;
        }

        if (!isInMap(x, y)) {
            board.addLogEntry("WARN: ADD_CAR failed, out of map: " + carId);
            return;
        }

        if (board.hasBlock(x, y)) {
            board.addLogEntry("WARN: ADD_CAR failed, blocked position: " + carId);
            return;
        }

        mq.declareCarQueue(carId);

        board.addCar(carId);
        board.setPosition(carId, x, y);
        board.setStatus(carId, CarStatus.IDLE.name());
        board.setDynamicBlock(x, y, true);
        board.appendTrace(carId, board.getCurrentTick(), x, y);

        illuminateInitialArea(x, y);

        board.addLogEntry("INFO: ADD_CAR success: " + carId + " at (" + x + "," + y + ")");
        mq.broadcastRefreshAll(board.getCurrentTick());
    }

    private boolean isInMap(int x, int y) {
        return x >= 0 && x < board.getMapWidth()
                && y >= 0 && y < board.getMapHeight();
    }

    private void illuminateInitialArea(int x, int y) {
        int radius = RedisKeys.VISION_RANGE;

        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int nx = x + dx;
                int ny = y + dy;

                if (isInMap(nx, ny)) {
                    board.exploreCell(nx, ny);
                }
            }
        }
    }

    // =========================================================
    // 知识源反馈处理
    // =========================================================

    private void handleTaskReady(JSONObject data) {
        int carCount = data.getIntValue("carCount");
        int mapWidth = data.getIntValue("mapWidth");
        int mapHeight = data.getIntValue("mapHeight");

        board.addLogEntry(
                "INFO: TASK_READY, carCount=" + carCount
                        + ", map=" + mapWidth + "x" + mapHeight
        );

        mq.broadcastRefreshAll(board.getCurrentTick());
    }

    private void handleTargetAssigned(JSONObject data) {
        /*
         * 推荐 TargetPlanner 回复格式：
         * {
         *   "assignedCars": [
         *      {"carId":"Car001","targetX":10,"targetY":8}
         *   ]
         * }
         */
        JSONArray assignedCars = data.getJSONArray("assignedCars");

        if (assignedCars == null) {
            String carId = data.getString("carId");
            if (carId != null) {
                board.setStatus(carId, CarStatus.WAITING_ROUTE.name());
                board.addLogEntry("INFO: target assigned: " + carId);
            }
            return;
        }

        for (int i = 0; i < assignedCars.size(); i++) {
            JSONObject car = assignedCars.getJSONObject(i);
            String carId = car.getString("carId");

            if (carId != null && board.carExists(carId)) {
                board.setStatus(carId, CarStatus.WAITING_ROUTE.name());
                board.addLogEntry("INFO: target assigned: " + carId);
            }
        }
    }

    private void handleRoutePlanned(JSONObject data) {
        String carId = data.getString("carId");
        boolean routeFound = data.getBooleanValue("routeFound");

        if (carId == null || !board.carExists(carId)) {
            return;
        }

        if (routeFound) {
            board.setStatus(carId, CarStatus.READY.name());
            board.incrementRoutePlanCount(carId);
            board.addLogEntry("INFO: route planned: " + carId);
        } else {
            board.clearRoute(carId);
            board.clearTarget(carId);
            board.setStatus(carId, CarStatus.IDLE.name());
            board.addLogEntry("WARN: route not found: " + carId);
        }
    }

    private void handleMoved(JSONObject data) {
        String carId = data.getString("carId");
        int x = data.getIntValue("x");
        int y = data.getIntValue("y");

        board.addLogEntry("INFO: " + carId + " moved to (" + x + "," + y + ")");
    }

    private void handleBlocked(JSONObject data) {
        String carId = data.getString("carId");
        if (carId == null || !board.carExists(carId)) {
            return;
        }

        board.incrementBlockedCount(carId);
        board.addLogEntry("WARN: " + carId + " blocked");
    }

    private void handleRouteDone(JSONObject data) {
        String carId = data.getString("carId");
        if (carId == null || !board.carExists(carId)) {
            return;
        }

        board.clearTarget(carId);
        board.clearRoute(carId);
        board.setStatus(carId, CarStatus.IDLE.name());

        board.addLogEntry("INFO: route done: " + carId);
    }

    // =========================================================
    // Tick 调度
    // =========================================================

    private void tick() {
        Map<String, String> config = board.getTaskConfig();
        String taskStatus = config == null
                ? TaskStatus.INIT.name()
                : config.getOrDefault("taskStatus", TaskStatus.INIT.name());

        long currentTick = board.getCurrentTick();

        if (!TaskStatus.RUNNING.name().equals(taskStatus)) {
            mq.broadcastRefreshAll(currentTick);
            return;
        }

        currentTick++;
        board.setCurrentTick(currentTick);

        double coverage = board.getExploredPercent();
        if (coverage >= 99.9) {
            config.put("taskStatus", TaskStatus.FINISHED.name());
            board.setTaskConfig(config);

            board.addLogEntry("INFO: task finished, coverage=" + coverage);
            mq.broadcastRefreshAll(currentTick);
            return;
        }

        List<String> cars = board.getCarList();

        for (String carId : cars) {
            dispatchCar(carId, config, currentTick);
        }

        board.saveCoverageHistory(currentTick, coverage);
        mq.broadcastRefreshAll(currentTick);
    }

    private void dispatchCar(String carId, Map<String, String> config, long currentTick) {
        String status = board.getStatus(carId);

        if (status == null) {
            return;
        }

        if (CarStatus.IDLE.name().equals(status)) {
            mq.assignTarget(carId);
        } else if (CarStatus.WAITING_ROUTE.name().equals(status)) {
            String algorithm = config.getOrDefault("algorithm", "BFS");
            mq.planRoute(carId, algorithm);
        } else if (CarStatus.READY.name().equals(status)) {
            mq.sendTickMove(carId);
        } else if (CarStatus.BLOCKED.name().equals(status)) {
            handleBlockedTimeout(carId, currentTick);
        }
    }

    private void handleBlockedTimeout(String carId, long currentTick) {
        long blockedTick = board.getBlockedTick(carId);
        long blockedDuration = currentTick - blockedTick;

        if (blockedDuration >= RedisKeys.BLOCKED_TIMEOUT_TICKS) {
            board.clearRoute(carId);
            board.clearTarget(carId);
            board.setStatus(carId, CarStatus.IDLE.name());

            board.addLogEntry("WARN: " + carId + " blocked timeout, reset to IDLE");
        }
    }

    // =========================================================
    // 工具方法
    // =========================================================

    private Map<String, Object> jsonObjectToMap(JSONObject obj) {
        if (obj == null) {
            return new HashMap<>();
        }

        Map<String, Object> map = new HashMap<>();
        for (String key : obj.keySet()) {
            Object value = obj.get(key);
            map.put(key, value);
        }
        return map;
    }
}