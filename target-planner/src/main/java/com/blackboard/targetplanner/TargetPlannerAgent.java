package com.blackboard.targetplanner;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.blackboard.api.Blackboard;
import com.blackboard.api.MessageQueue;
import com.blackboard.api.impl.BlackboardImpl;
import com.blackboard.api.impl.MessageQueueImpl;
import com.blackboard.constant.MQKeys;
import com.blackboard.model.Message;
import com.blackboard.model.Position;
import com.blackboard.targetplanner.service.TargetPlannerService;
import com.blackboard.targetplanner.service.TargetSelectionMetrics;
import com.blackboard.targetplanner.service.TargetSelectionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * TargetPlanner 知识源。
 *
 * <p>职责：监听 ASSIGN_TARGET，读取地图探索状态/车辆位置/已有目标，使用贪心策略选择目标点，
 * 写入 CarID:Target，并向 Controller 回复 TARGET_ASSIGNED。</p>
 *
 * <p>重要约束：本组件不读取 staticBlock/mapBlock。静态障碍物只由真实移动/碰撞检测发现，
 * TargetPlanner 不提前知道，也不记录。</p>
 */
public class TargetPlannerAgent {
    private static final Logger log = LoggerFactory.getLogger(TargetPlannerAgent.class);

    private final Blackboard board;
    private final MessageQueue mq;
    private final TargetPlannerService plannerService;

    public TargetPlannerAgent(Blackboard board, MessageQueue mq) {
        this.board = board;
        this.mq = mq;
        this.plannerService = new TargetPlannerService(board);
    }

    public void start() {
        mq.connect();
        mq.subscribeTargetPlanner(this::handleMessageSafely);
        log.info("TargetPlannerAgent started. Waiting for {} messages.", MQKeys.CMD_ASSIGN_TARGET);
    }

    private void handleMessageSafely(String rawMessage) {
        try {
            handleMessage(rawMessage);
        } catch (Exception e) {
            log.error("TargetPlanner failed to handle message: {}", rawMessage, e);
            String carId = tryReadCarId(rawMessage);
            if (carId != null) {
                replyNotAssigned(carId, "error:" + e.getClass().getSimpleName());
            }
        }
    }

    private void handleMessage(String rawMessage) {
        Message message = JSON.parseObject(rawMessage, Message.class);
        if (message == null || !MQKeys.CMD_ASSIGN_TARGET.equals(message.getCmd())) {
            log.debug("TargetPlanner ignored message: {}", rawMessage);
            return;
        }

        JSONObject data = JSON.parseObject(JSON.toJSONString(message.getData()));
        String carId = data.getString("carId");
        if (carId == null || carId.isBlank()) {
            throw new IllegalArgumentException("ASSIGN_TARGET missing carId");
        }

        TargetSelectionResult result = plannerService.assignTarget(carId);
        logTargetMetrics(result);
        if (result.isAssigned()) {
            replyAssigned(result);
            Position target = result.getTarget();
            log.info("Target assigned for {} -> ({},{}), metrics={}", carId, target.getX(), target.getY(), metricsText(result.getMetrics()));
        } else {
            replyNotAssigned(carId, result.getReason());
            log.info("Target not assigned for {}, reason={}, metrics={}", carId, result.getReason(), metricsText(result.getMetrics()));
        }
    }

    private void replyAssigned(TargetSelectionResult result) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("carId", result.getCarId());
        item.put("targetX", result.getTarget().getX());
        item.put("targetY", result.getTarget().getY());
        JSONArray assignedCars = new JSONArray();
        assignedCars.add(item);
        mq.replyTargetAssigned(assignedCars.toJSONString());
    }

    private void replyNotAssigned(String carId, String reason) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("carId", carId);
        item.put("assigned", false);
        item.put("reason", reason);
        JSONArray assignedCars = new JSONArray();
        assignedCars.add(item);
        mq.replyTargetAssigned(assignedCars.toJSONString());
    }

    private void logTargetMetrics(TargetSelectionResult result) {
        Map<String, Object> logItem = new LinkedHashMap<>();
        logItem.put("type", "TARGET_PLAN_METRICS");
        logItem.put("carId", result.getCarId());
        logItem.put("assigned", result.isAssigned());
        logItem.put("reason", result.getReason());
        if (result.getTarget() != null) {
            logItem.put("targetX", result.getTarget().getX());
            logItem.put("targetY", result.getTarget().getY());
        }
        TargetSelectionMetrics metrics = result.getMetrics();
        if (metrics != null) {
            logItem.putAll(metrics.toMap());
        }
        logItem.put("time", System.currentTimeMillis());
        try {
            board.addLogEntry(JSON.toJSONString(logItem));
        } catch (Exception e) {
            log.debug("Failed to write target metrics to explorationLog. This does not affect target assignment.", e);
        }
    }

    private String metricsText(TargetSelectionMetrics metrics) {
        return metrics == null ? "none" : metrics.toMap().toString();
    }

    private String tryReadCarId(String rawMessage) {
        try {
            JSONObject root = JSON.parseObject(rawMessage);
            JSONObject data = root.getJSONObject("data");
            return data == null ? null : data.getString("carId");
        } catch (Exception ignored) {
            return null;
        }
    }

    public static void main(String[] args) throws InterruptedException {
        String redisHost = env("REDIS_HOST", "localhost");
        int redisPort = Integer.parseInt(env("REDIS_PORT", "6379"));
        String rabbitHost = env("RABBITMQ_HOST", "localhost");
        int rabbitPort = Integer.parseInt(env("RABBITMQ_PORT", "5672"));

        Blackboard board = new BlackboardImpl(redisHost, redisPort);
        MessageQueue mq = new MessageQueueImpl(rabbitHost, rabbitPort);
        new TargetPlannerAgent(board, mq).start();
        new CountDownLatch(1).await();
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
