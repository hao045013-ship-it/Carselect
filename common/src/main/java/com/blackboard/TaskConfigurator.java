package com.blackboard;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.blackboard.api.Blackboard;
import com.blackboard.api.MessageQueue;
import com.blackboard.constant.MQKeys;
import com.blackboard.model.CarStatus;

import java.util.HashMap;
import java.util.Map;

public class TaskConfigurator {

    private final Blackboard board;
    private final MessageQueue mq;

    public TaskConfigurator(Blackboard board, MessageQueue mq) {
        this.board = board;
        this.mq = mq;
    }

    /**
     * 解析前端传来的配置，并一次性完成系统初始化
     *
     * @param configJson 前端传来的配置 JSON 字符串
     */
    public void initFromConfig(String configJson) {
        JSONObject config = JSONObject.parseObject(configJson);

        // 1. 设置地图尺寸
        int mapWidth = config.getIntValue("mapWidth");
        int mapHeight = config.getIntValue("mapHeight");
        setMapSize(mapWidth, mapHeight);

        // 2. 清除并设置静态障碍物（如果配置中提供了 mapData）
        board.clearStaticBlocks();
        JSONArray mapData = config.getJSONArray("mapData");
        if (mapData != null && !mapData.isEmpty()) {
            loadMapData(mapData);
        }

        // 3. 初始化车辆
        JSONArray cars = config.getJSONArray("cars");
        if (cars != null) {
            for (int i = 0; i < cars.size(); i++) {
                JSONObject car = cars.getJSONObject(i);
                String carId = car.getString("carId");
                int x = car.getIntValue("x");
                int y = car.getIntValue("y");
                initializeCar(carId, x, y);
            }
        }
    }

    private void setMapSize(int width, int height) {
        Map<String, String> config = new HashMap<>();
        config.put("mapWidth", String.valueOf(width));
        config.put("mapHeight", String.valueOf(height));
        board.setTaskConfig(config);
    }

    private void loadMapData(JSONArray mapData) {
        for (int r = 0; r < mapData.size(); r++) {
            JSONArray row = mapData.getJSONArray(r);
            for (int c = 0; c < row.size(); c++) {
                if (row.getIntValue(c) == 1) {
                    board.setStaticBlock(r, c, true);
                }
            }
        }
    }

    private void initializeCar(String carId, int x, int y) {
        // 1. 声明小车专属队列
        mq.declareCarQueue(carId);

        // 2. 注册到黑板
        board.addCar(carId);

        // 3. 设置初始状态和位置
        board.setPosition(carId, x, y);
        board.setStatus(carId, CarStatus.IDLE.name());
        board.setDynamicBlock(y, x, true);

        // 4. 记录初始轨迹
        board.appendTrace(carId, 0, x, y);
    }

    public void loadMapFromArray(int[][] mapData, int mapWidth, int mapHeight) {
        // 1. 可选：更新地图尺寸（如果与当前不同）
        setMapSize(mapWidth, mapHeight);

        // 2. 清除现有静态障碍
        board.clearStaticBlocks();

        // 3. 遍历并设置障碍物
        for (int r = 0; r < mapData.length && r < mapHeight; r++) {
            int[] row = mapData[r];
            for (int c = 0; c < row.length && c < mapWidth; c++) {
                if (row[c] == 1) {
                    board.setStaticBlock(r, c, true);//
                }
            }
        }
    }
}