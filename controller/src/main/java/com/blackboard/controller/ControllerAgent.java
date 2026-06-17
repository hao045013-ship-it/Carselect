package com.blackboard.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.blackboard.api.Blackboard;
import com.blackboard.api.MessageQueue;
import com.blackboard.api.impl.MessageQueueImpl;
import com.blackboard.constant.MQKeys;
import com.blackboard.constant.RedisKeys;
import com.blackboard.model.CarStatus;
import com.blackboard.model.Position;
import com.blackboard.model.SimState;
import com.blackboard.model.TaskStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class ControllerAgent {

    private final Blackboard board;
    private final MessageQueue mq;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

    private volatile boolean started = false;
    private String currentSessionId;

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

                case MQKeys.CMD_ADD_CARS_BATCH:
                    handleAddCarsBatch(data);
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
        purgeSimulationCommandQueues(data == null ? 0 : data.getIntValue("carCount", data.getIntValue("robotCount", 0)));

        mq.sendToQueue(
                MQKeys.TASK_CONFIG_CMD,
                MQKeys.CMD_FORWARD_CONFIG,
                map
        );

        board.addLogEntry("INFO: Controller forward SET_CONFIG to TaskConfigurator");
    }

    private void handleReset() {
        purgeSimulationCommandQueues(board.getCarList().size());
        mq.sendToQueue(
                MQKeys.TASK_CONFIG_CMD,
                MQKeys.CMD_FORWARD_RESET,
                Collections.emptyMap()
        );

        board.addLogEntry("INFO: Controller forward RESET to TaskConfigurator");
        mq.broadcastEvent(MQKeys.CMD_RESET, Collections.emptyMap());
    }

    private void purgeSimulationCommandQueues(int carCount) {
        if (mq instanceof MessageQueueImpl) {
            ((MessageQueueImpl) mq).purgeSimulationCommandQueues(Math.max(carCount, board.getCarList().size()));
        }
    }

    private void handleStart() {
        purgeSimulationCommandQueues(board.getCarList().size());
        // 生成 sessionId
        currentSessionId = java.util.UUID.randomUUID().toString();
        Map<String, String> config = board.getTaskConfig();

        if (config == null) {
            config = new HashMap<>();
        }

        board.setCurrentTick(0L);
        config.put("taskStatus", TaskStatus.INIT.name());
        board.setTaskConfig(config);

        //String sessionId = java.util.UUID.randomUUID().toString();

        prepareCarsForStart();

        String initialStateJson = buildStartSnapshotJson();

        Map<String, Object> startData = new HashMap<>();
        startData.put("tick", 0L);
        startData.put("sessionId", currentSessionId);
        startData.put("initialStateJson", initialStateJson);

        mq.broadcastEvent(MQKeys.CMD_START, startData);
        broadcastSnapshot(0L);

        waitForRecorders();

        config.put("taskStatus", TaskStatus.RUNNING.name());
        board.setTaskConfig(config);

        board.addLogEntry("INFO: task started");
        broadcastSnapshot(0L);
    }

    private void prepareCarsForStart() {
        for (String carId : board.getCarList()) {
            board.clearRoute(carId);
            board.clearTarget(carId);
            board.setStatus(carId, CarStatus.IDLE.name());

            Map<String, String> pos = board.getPosition(carId);
            if (pos != null && pos.containsKey("x") && pos.containsKey("y")) {
                int x = Integer.parseInt(pos.get("x"));
                int y = Integer.parseInt(pos.get("y"));
                board.appendTrace(carId, 0L, x, y);
            }
        }
    }

    private String buildStartSnapshotJson() {
        SimState state = new SimState();

        state.setMapWidth(board.getMapWidth());
        state.setMapHeight(board.getMapHeight());
        state.setMapView(board.getFullMapView());
        state.setStaticBlock(board.getFullStaticBlock());
        state.setDynamicBlock(board.getFullDynamicBlock());
        state.setExploredPercent(board.getExploredPercent());
        state.setTick(0L);
        state.setStatus(TaskStatus.INIT.name());

        Map<String, SimState.CarInfo> cars = new HashMap<>();

        for (String carId : board.getCarList()) {
            SimState.CarInfo info = new SimState.CarInfo();
            info.setCarId(carId);

            Map<String, String> pos = board.getPosition(carId);
            if (pos != null && pos.containsKey("x") && pos.containsKey("y")) {
                info.setPosition(new Position(
                        Integer.parseInt(pos.get("x")),
                        Integer.parseInt(pos.get("y"))
                ));
            }

            info.setStatus(CarStatus.IDLE.name());
            info.setTarget(null);
            info.setRouteList(new ArrayList<>());
            info.setStepsWalked(board.getSteps(carId));

            cars.put(carId, info);
        }

        state.setCars(cars);
        state.setStatsReport(null);
        state.setCoverageHistory(null);

        return state.toJson();
    }

    private void waitForRecorders() {
        try {
            Thread.sleep(200L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void handlePause() {
        Map<String, String> config = board.getTaskConfig();
        if (config == null) {
            config = new HashMap<>();
        }

        config.put("taskStatus", TaskStatus.PAUSED.name());
        board.setTaskConfig(config);

        board.addLogEntry("INFO: task paused");
        mq.broadcastEvent(MQKeys.CMD_PAUSE, Map.of("tick", board.getCurrentTick()));
        broadcastSnapshot(board.getCurrentTick());
    }

    private void handleResume() {
        Map<String, String> config = board.getTaskConfig();
        if (config == null) {
            config = new HashMap<>();
        }

        config.put("taskStatus", TaskStatus.RUNNING.name());
        board.setTaskConfig(config);

        board.addLogEntry("INFO: task resumed");
        mq.broadcastEvent(MQKeys.CMD_RESUME, Map.of("tick", board.getCurrentTick()));
        broadcastSnapshot(board.getCurrentTick());
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
        int row = data.containsKey("row") ? data.getIntValue("row") : data.getIntValue("y");
        int col = data.containsKey("col") ? data.getIntValue("col") : data.getIntValue("x");
        int x = col;
        int y = row;

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

        mq.declareCarQueue(carId);

        if (!board.tryAddCar(carId, y, x, CarStatus.IDLE.name())) {
            board.addLogEntry("WARN: ADD_CAR failed, blocked or occupied position: " + carId);
            return;
        }

        board.appendTrace(carId, board.getCurrentTick(), x, y);

        illuminateInitialArea(x, y);

        board.addLogEntry("INFO: ADD_CAR success: " + carId + " at (" + x + "," + y + ")");
        broadcastSnapshot(board.getCurrentTick());
    }

    private void handleAddCarsBatch(JSONObject data) {
        int requested = data == null ? 0 : data.getIntValue("count");
        if (requested <= 0 && data != null) {
            int targetCount = data.getIntValue("targetCount", 0);
            requested = Math.max(0, targetCount - board.getCarList().size());
        }
        if (requested <= 0) {
            board.addLogEntry("WARN: ADD_CARS_BATCH ignored, count <= 0");
            return;
        }

        int added = 0;
        int attempts = 0;
        int width = board.getMapWidth();
        int height = board.getMapHeight();
        int maxAttempts = Math.max(100, width * height * 3);

        while (added < requested && attempts < maxAttempts) {
            attempts++;
            int x = ThreadLocalRandom.current().nextInt(width);
            int y = ThreadLocalRandom.current().nextInt(height);
            if (!isFreeForCar(x, y)) {
                continue;
            }

            String carId = nextAvailableCarId();
            if (addCarUnchecked(carId, x, y)) {
                added++;
            }
        }

        if (added > 0) {
            board.addLogEntry("INFO: ADD_CARS_BATCH success: added=" + added + ", requested=" + requested);
            broadcastSnapshot(board.getCurrentTick());
        } else {
            board.addLogEntry("WARN: ADD_CARS_BATCH failed, no free cells found");
        }
    }

    private boolean addCarUnchecked(String carId, int x, int y) {
        mq.declareCarQueue(carId);

        if (!board.tryAddCar(carId, y, x, CarStatus.IDLE.name())) {
            return false;
        }

        board.appendTrace(carId, board.getCurrentTick(), x, y);
        illuminateInitialArea(x, y);
        return true;
    }

    private boolean isFreeForCar(int x, int y) {
        return isInMap(x, y) && !board.hasBlock(y, x);
    }

    private String nextAvailableCarId() {
        int n = 1;
        String carId = "Car" + String.format("%03d", n);
        while (board.carExists(carId)) {
            n++;
            carId = "Car" + String.format("%03d", n);
        }
        return carId;
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
                    board.exploreCell(ny, nx);
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

        broadcastSnapshot(board.getCurrentTick());
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
                boolean assigned = !data.containsKey("assigned") || data.getBooleanValue("assigned");
                if (assigned) {
                    board.setStatus(carId, CarStatus.WAITING_ROUTE.name());
                    board.addLogEntry("INFO: target assigned: " + carId);
                } else {
                    board.setStatus(carId, CarStatus.IDLE.name());
                    board.addLogEntry("WARN: target not assigned: " + carId + ", reason=" + data.getString("reason"));
                }
            }
            return;
        }

        for (int i = 0; i < assignedCars.size(); i++) {
            JSONObject car = assignedCars.getJSONObject(i);
            String carId = car.getString("carId");

            if (carId != null && board.carExists(carId)) {
                boolean assigned = !car.containsKey("assigned") || car.getBooleanValue("assigned");
                if (assigned) {
                    board.setStatus(carId, CarStatus.WAITING_ROUTE.name());
                    board.addLogEntry("INFO: target assigned: " + carId);
                } else {
                    board.setStatus(carId, CarStatus.IDLE.name());
                    board.addLogEntry("WARN: target not assigned: " + carId + ", reason=" + car.getString("reason"));
                }
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

        mq.broadcastEvent(MQKeys.CMD_ROUTE_PLANNED, jsonObjectToMap(data));
    }

    private void handleMoved(JSONObject data) {
        String carId = data.getString("carId");
        int x = data.getIntValue("x");
        int y = data.getIntValue("y");

        board.addLogEntry("INFO: " + carId + " moved to (" + x + "," + y + ")");
        mq.broadcastEvent(MQKeys.CMD_MOVED, jsonObjectToMap(data));
    }

    private void handleBlocked(JSONObject data) {
        String carId = data.getString("carId");
        if (carId == null || !board.carExists(carId)) {
            return;
        }

        board.clearRoute(carId);
        board.incrementBlockedCount(carId);

        // 遇到障碍物后，重新规划当前目标
        board.setStatus(carId, CarStatus.WAITING_ROUTE.name());

        board.addLogEntry("WARN: " + carId + " blocked, replan route");
        mq.broadcastEvent(MQKeys.CMD_BLOCKED, jsonObjectToMap(data));
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
        mq.broadcastEvent(MQKeys.CMD_ROUTE_DONE, jsonObjectToMap(data));
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
            broadcastSnapshot(currentTick);
            return;
        }

        currentTick++;
        board.setCurrentTick(currentTick);

        double coverage = board.getExploredPercent();
        if (coverage >= 99.999) {
            config.put("taskStatus", TaskStatus.FINISHED.name());
            board.setTaskConfig(config);

            board.addLogEntry("INFO: task finished, coverage=" + coverage);
            // 广播任务完成事件
            mq.broadcastEvent(MQKeys.CMD_TASK_FINISHED, Map.of("tick", currentTick));
            broadcastSnapshot(currentTick);
            return;
        }

        List<String> cars = board.getCarList();
        List<String> idleCars = new ArrayList<>();
        for (String carId : cars) {
            if (CarStatus.IDLE.name().equals(board.getStatus(carId))) {
                idleCars.add(carId);
            } else {
                dispatchCar(carId, config, currentTick);
            }
        }
        if (!idleCars.isEmpty()) {
            mq.assignTargets(idleCars);
        }

        board.saveCoverageHistory(currentTick, coverage);
        broadcastSnapshot(currentTick);
    }

    private void dispatchCar(String carId, Map<String, String> config, long currentTick) {
        String status = board.getStatus(carId);

        if (status == null) {
            return;
        }

        if (CarStatus.WAITING_ROUTE.name().equals(status)) {
            String algorithm = config.getOrDefault("algorithm", "A_STAR");
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

    private void broadcastSnapshot(long tick) {
        SimState snapshot = buildCurrentSnapshot(tick);
        Map<String, Object> data = new HashMap<>();
        data.put("tick", tick);
        data.put("stateJson", snapshot.toJson());
        mq.broadcastEvent(MQKeys.CMD_REFRESH_ALL, data);
    }

    private SimState buildCurrentSnapshot(long tick) {
        SimState state = new SimState();
        state.setMapWidth(board.getMapWidth());
        state.setMapHeight(board.getMapHeight());
        state.setMapView(board.getFullMapView());
        state.setStaticBlock(board.getFullStaticBlock());
        state.setDynamicBlock(board.getFullDynamicBlock());
        state.setExploredPercent(board.getExploredPercent());
        state.setTick(tick);

        Map<String, String> config = board.getTaskConfig();
        if (config != null) {
            state.setStatus(config.get("taskStatus"));
        }
        state.setStatsReport(board.getStatsReport());
        state.setCoverageHistory(board.getCoverageHistory());

        Map<String, SimState.CarInfo> cars = new HashMap<>();
        for (String carId : board.getCarList()) {
            SimState.CarInfo info = new SimState.CarInfo();
            info.setCarId(carId);

            Map<String, String> pos = board.getPosition(carId);
            if (pos != null && pos.containsKey("x") && pos.containsKey("y")) {
                info.setPosition(new Position(
                        Integer.parseInt(pos.get("x")),
                        Integer.parseInt(pos.get("y"))
                ));
            }

            Map<String, String> target = board.getTarget(carId);
            if (target != null && target.containsKey("x") && target.containsKey("y")) {
                info.setTarget(new Position(
                        Integer.parseInt(target.get("x")),
                        Integer.parseInt(target.get("y"))
                ));
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
        state.setCars(cars);
        return state;
    }

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
