package com.blackboard.car;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.blackboard.api.Blackboard;
import com.blackboard.api.MessageQueue;
import com.blackboard.constant.MQKeys;
import com.blackboard.constant.RedisKeys;
import com.blackboard.model.CarStatus;
import com.blackboard.model.Position;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CarAgent {

    private final String carId;
    private final Blackboard board;
    private final MessageQueue mq;
    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newSingleThreadScheduledExecutor();

    public CarAgent(String carId, Blackboard board, MessageQueue mq) {
        this.carId = carId;
        this.board = board;
        this.mq = mq;
    }

    public void start() {
        mq.subscribeCar(carId, this::handleMessage);
        sendHeartbeat();
        heartbeatScheduler.scheduleAtFixedRate(this::sendHeartbeatSafely, 5, 5, TimeUnit.SECONDS);
        System.out.println(carId + " subscribed.");
    }

    public void stop() {
        heartbeatScheduler.shutdownNow();
    }

    private void sendHeartbeatSafely() {
        try {
            sendHeartbeat();
        } catch (Exception e) {
            System.err.println(carId + " registry heartbeat failed: " + e.getMessage());
        }
    }

    private void sendHeartbeat() {
        Map<String, Object> data = new HashMap<>();
        data.put("entityType", "CAR");
        data.put("carId", carId);
        data.put("status", board.getStatus(carId));
        mq.sendToQueue(MQKeys.REGISTRY_CMD, MQKeys.CMD_HEARTBEAT, data);
    }

    private void handleMessage(String messageJson) {
        JSONObject msg = JSON.parseObject(messageJson);
        String cmd = msg.getString("cmd");

        if ("TICK_MOVE".equals(cmd)) {
            handleTickMove();
        }
    }

    private void handleTickMove() {
        String status = board.getStatus(carId);

        if (!CarStatus.READY.name().equals(status)) {
            return;
        }

        Map<String, String> posMap = board.getPosition(carId);
        if (posMap == null || posMap.isEmpty()) {
            return;
        }

        int oldX = Integer.parseInt(posMap.get("x"));
        int oldY = Integer.parseInt(posMap.get("y"));

        String nextJson = board.peekRoute(carId);

        if (nextJson == null) {
            handleRouteDone(oldX, oldY);
            return;
        }

        Position next = Position.fromJson(nextJson);
        int newX = next.getX();
        int newY = next.getY();

        if (board.hasBlock(newY, newX)) {
            handleBlocked(oldX, oldY, newX, newY);
            return;
        }

        moveOneStep(oldX, oldY, newX, newY);
    }

    private void moveOneStep(int oldX, int oldY, int newX, int newY) {
        board.setStatus(carId, CarStatus.MOVING.name());

        // 消费路径中的下一步
        boolean moved = board.atomicMove(carId, oldX, oldY, newX, newY, RedisKeys.VISION_RANGE);
        if (!moved) {
            handleBlocked(oldX, oldY, newX, newY);
            return;
        }

        board.popRoute(carId);

        // 原子移动：更新位置、dynamicBlock、mapView、steps
        long tick = board.getCurrentTick();
        board.appendTrace(carId, tick, newX, newY);

        long remain = board.getRouteLength(carId);

        if (remain == 0) {
            board.setStatus(carId, CarStatus.IDLE.name());
            mq.replyRouteDone(carId, newX, newY);
        } else {
            board.setStatus(carId, CarStatus.READY.name());
            mq.replyMoved(carId, newX, newY);
        }
    }

    private void handleBlocked(int oldX, int oldY, int blockedX, int blockedY) {
        long tick = board.getCurrentTick();
        board.exploreCell(blockedY, blockedX);
        board.clearRoute(carId);
        board.setBlockedTick(carId, tick);
        board.incrementBlockedCount(carId);
        board.setStatus(carId, CarStatus.BLOCKED.name());

        mq.replyBlocked(carId, oldX, oldY, blockedX, blockedY);
    }

    private void handleRouteDone(int x, int y) {
        board.clearRoute(carId);
        board.clearTarget(carId);
        board.setStatus(carId, CarStatus.IDLE.name());

        mq.replyRouteDone(carId, x, y);
    }
    //
}
