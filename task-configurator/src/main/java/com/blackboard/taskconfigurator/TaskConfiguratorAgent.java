package com.blackboard.taskconfigurator;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.blackboard.api.Blackboard;
import com.blackboard.api.MessageQueue;
import com.blackboard.constant.MQKeys;
import com.blackboard.constant.RedisKeys;
import com.blackboard.model.CarStatus;
import com.blackboard.model.TaskStatus;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TaskConfiguratorAgent {

    private final Blackboard board;
    private final MessageQueue mq;
    private final String agentId = "task-configurator-" + UUID.randomUUID();

    public TaskConfiguratorAgent(Blackboard board, MessageQueue mq) {
        this.board = board;
        this.mq = mq;
    }

    public void start() {
        registerKnowledgeSource();
        mq.subscribeTaskConfig(this::handleMessage);
    }

    private void registerKnowledgeSource() {
        Map<String, Object> data = new HashMap<>();
        data.put("entityType", "KNOWLEDGE_SOURCE");
        data.put("agentId", agentId);
        data.put("type", "TASK_CONFIGURATOR");
        data.put("status", "ONLINE");
        mq.sendToQueue(MQKeys.REGISTRY_CMD, MQKeys.CMD_REGISTER, data);
    }

    private void handleMessage(String messageJson) {
        JSONObject msg = JSON.parseObject(messageJson);
        String cmd = msg.getString("cmd");
        JSONObject data = msg.getJSONObject("data");

        if (MQKeys.CMD_FORWARD_CONFIG.equals(cmd)) {
            handleForwardConfig(data);
        } else if (MQKeys.CMD_FORWARD_RESET.equals(cmd)) {
            handleForwardReset();
        } else if (MQKeys.CMD_LOAD_MAP_FILE.equals(cmd)) {
            handleLoadMapFile(data);
        }
    }

    private void handleForwardConfig(JSONObject data) {
        board.clearAll();

        int mapWidth = data.getIntValue("mapWidth", RedisKeys.DEFAULT_MAP_WIDTH);
        int mapHeight = data.getIntValue("mapHeight", RedisKeys.DEFAULT_MAP_HEIGHT);
        int carCount = data.getIntValue("carCount", 5);
        int obstacleDensity = data.getIntValue("obstacleDensity", 10);
        String algorithm = data.getString("algorithm");
        if (algorithm == null || algorithm.isBlank()) {
            algorithm = "A_STAR";
        }

        Map<String, String> config = new HashMap<>();
        config.put("mapWidth", String.valueOf(mapWidth));
        config.put("mapHeight", String.valueOf(mapHeight));
        config.put("carCount", String.valueOf(carCount));
        config.put("obstacleDensity", String.valueOf(obstacleDensity));
        config.put("algorithm", algorithm);
        config.put("taskStatus", TaskStatus.INIT.name());

        board.setTaskConfig(config);
        board.setCurrentTick(0L);

        boolean generateObstacles = data.getBooleanValue("generateObstacles");
        if (generateObstacles) {
            board.randomStaticBlocks(obstacleDensity / 100.0);
        } else {
            board.clearStaticBlocks();
        }

        JSONArray cars = data.getJSONArray("cars");
        if (cars != null && !cars.isEmpty()) {
            initCarsFromConfig(cars);
        } else if (data.getBooleanValue("generateDefaultCars")) {
            initDefaultCars(carCount, mapWidth, mapHeight);
        }

        mq.declareAllQueues(carCount);
        mq.replyTaskReady(carCount, mapWidth, mapHeight);
    }

    private void initCarsFromConfig(JSONArray cars) {
        for (int i = 0; i < cars.size(); i++) {
            JSONObject car = cars.getJSONObject(i);

            String carId = car.getString("carId");
            int row = car.containsKey("row") ? car.getIntValue("row") : car.getIntValue("y");
            int col = car.containsKey("col") ? car.getIntValue("col") : car.getIntValue("x");
            int x = col;
            int y = row;

            initOneCar(carId, x, y);
        }
    }

    private void initDefaultCars(int carCount, int mapWidth, int mapHeight) {
        int[][] defaultPositions = {
                {1, 1},
                {mapWidth - 2, 1},
                {1, mapHeight - 2},
                {mapWidth - 2, mapHeight - 2},
                {mapWidth / 2, mapHeight / 2}
        };

        for (int i = 0; i < carCount; i++) {
            String carId = "Car" + String.format("%03d", i + 1);

            int x;
            int y;

            if (i < defaultPositions.length) {
                x = defaultPositions[i][0];
                y = defaultPositions[i][1];
            } else {
                x = 1 + i % Math.max(1, mapWidth - 2);
                y = 1 + i % Math.max(1, mapHeight - 2);
            }

            initOneCar(carId, x, y);
        }
    }

    private void initOneCar(String carId, int x, int y) {
        if (carId == null || carId.isBlank()) {
            return;
        }

        if (x < 0 || x >= board.getMapWidth() || y < 0 || y >= board.getMapHeight()) {
            board.addLogEntry("WARN: init car failed, out of map: " + carId);
            return;
        }

        if (board.hasStaticBlock(y, x)) {
            board.setStaticBlock(y, x, false);
        }
        if (board.hasDynamicBlock(y, x)) {
            board.addLogEntry("WARN: init car failed, occupied: " + carId);
            return;
        }

        board.addCar(carId);
        board.setPosition(carId, y, x);
        board.setStatus(carId, CarStatus.IDLE.name());
        board.setDynamicBlock(y, x, true);
        board.appendTrace(carId, 0L, x, y);

        illuminateInitialArea(x, y);
        registerCarWithRegistry(carId, y, x);
    }

    private void illuminateInitialArea(int x, int y) {
        board.revealVision(x, y, RedisKeys.VISION_RANGE);
    }

    private void registerCarWithRegistry(String carId, int row, int col) {
        Map<String, Object> data = new HashMap<>();
        data.put("entityType", "CAR");
        data.put("carId", carId);
        data.put("row", row);
        data.put("col", col);
        data.put("status", CarStatus.IDLE.name());
        mq.sendToQueue(MQKeys.REGISTRY_CMD, MQKeys.CMD_REGISTER, data);
    }

    private void handleForwardReset() {
        board.clearAll();
        Map<String, String> config = new HashMap<>();
        config.put("taskStatus", TaskStatus.INIT.name());
        board.setTaskConfig(config);
        board.setCurrentTick(0L);
        mq.broadcastRefreshAll(0L);
    }

    private void handleLoadMapFile(JSONObject data) {
        board.clearAll();

        int mapWidth = data.getIntValue("mapWidth");
        int mapHeight = data.getIntValue("mapHeight");

        Map<String, String> config = new HashMap<>();
        config.put("mapWidth", String.valueOf(mapWidth));
        config.put("mapHeight", String.valueOf(mapHeight));
        config.put("taskStatus", TaskStatus.INIT.name());
        config.put("algorithm", data.getString("algorithm") == null ? "A_STAR" : data.getString("algorithm"));

        board.setTaskConfig(config);
        board.setCurrentTick(0L);

        JSONArray mapData = data.getJSONArray("mapData");
        if (mapData != null) {
            for (int y = 0; y < mapData.size(); y++) {
                JSONArray row = mapData.getJSONArray(y);
                for (int x = 0; x < row.size(); x++) {
                    int val = row.getIntValue(x);
                    if (val == 1) {
                        // setStaticBlock uses (row, col), so pass (y, x).
                        board.setStaticBlock(y, x, true);
                    }
                }
            }
        }

        mq.broadcastRefreshAll(0L);//
    }
}
