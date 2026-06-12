package com.blackboard.car;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.blackboard.api.Blackboard;
import com.blackboard.api.MessageQueue;
import com.blackboard.constant.RedisKeys;
import com.blackboard.model.CarStatus;
import com.blackboard.model.Position;

import java.util.Map;

public class CarAgent {

    private final String carId;
    private final Blackboard board;
    private final MessageQueue mq;

    public CarAgent(String carId, Blackboard board, MessageQueue mq) {
        this.carId = carId;
        this.board = board;
        this.mq = mq;
    }

    public void start() {
        mq.subscribeCar(carId, this::handleMessage);
        System.out.println(carId + " subscribed.");
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
        board.popRoute(carId);

        // 原子移动：更新位置、dynamicBlock、mapView、steps
        board.atomicMove(carId, oldX, oldY, newX, newY, RedisKeys.VISION_RANGE);

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
}