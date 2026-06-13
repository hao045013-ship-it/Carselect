package com.blackboard.obstaclemanager;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.blackboard.api.Blackboard;
import com.blackboard.api.MessageQueue;
import com.blackboard.constant.MQKeys;

import java.util.Map;

public class ObstacleManagerAgent {

    private final Blackboard board;
    private final MessageQueue mq;

    public ObstacleManagerAgent(Blackboard board, MessageQueue mq) {
        this.board = board;
        this.mq = mq;
    }

    public void start() {
        mq.subscribeObstacle(this::handleMessage);
    }

    private void handleMessage(String messageJson) {
        JSONObject msg = JSON.parseObject(messageJson);
        String cmd = msg.getString("cmd");
        JSONObject data = msg.getJSONObject("data");
        if (data == null) data = new JSONObject();

        if (MQKeys.CMD_SET_OBSTACLE.equals(cmd)) {
            handleSetObstacle(data);
        } else if (MQKeys.CMD_RANDOM_OBSTACLE.equals(cmd)) {
            handleRandomObstacle(data);
        } else if (MQKeys.CMD_CLEAR_OBSTACLE.equals(cmd)) {
            handleClearObstacle();
        }
    }

    private void handleSetObstacle(JSONObject data) {
        int row = data.getIntValue("row");
        int col = data.getIntValue("col");
        boolean value = data.getBooleanValue("value");

        if (!isInMap(row, col)) {
            board.addLogEntry("WARN: set obstacle out of map: (" + col + "," + row + ")");
            return;
        }

        // 检查是否有小车占用
        if (board.hasDynamicBlock(row, col)) {
            board.addLogEntry("WARN: cannot set obstacle, car at (" + col + "," + row + ")");
            return;
        }

        board.setStaticBlock(row, col, value);
        board.addLogEntry("INFO: obstacle (" + col + "," + row + ") = " + value);

        mq.broadcastRefreshAll(board.getCurrentTick());
    }

    private void handleRandomObstacle(JSONObject data) {
        int densityPercent = data.getIntValue("density", 10);
        double density = densityPercent / 100.0;

        board.clearStaticBlocks();
        board.randomStaticBlocks(density);

        // 确保小车当前位置不是静态障碍
        for (String carId : board.getCarList()) {
            Map<String, String> pos = board.getPosition(carId);
            if (pos == null || pos.isEmpty()) continue;
            int x = Integer.parseInt(pos.get("x"));
            int y = Integer.parseInt(pos.get("y"));
            board.setStaticBlock(y, x, false);  // (row=y, col=x)
        }

        board.addLogEntry("INFO: random obstacles generated, density=" + densityPercent + "%");
        mq.broadcastRefreshAll(board.getCurrentTick());
    }

    private void handleClearObstacle() {
        board.clearStaticBlocks();
        board.addLogEntry("INFO: all static obstacles cleared");
        mq.broadcastRefreshAll(board.getCurrentTick());
    }

    private boolean isInMap(int row, int col) {
        return row >= 0 && row < board.getMapHeight() && col >= 0 && col < board.getMapWidth();
    }
}