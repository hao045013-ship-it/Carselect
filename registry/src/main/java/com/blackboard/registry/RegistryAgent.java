package com.blackboard.registry;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.blackboard.api.Blackboard;
import com.blackboard.api.MessageQueue;
import com.blackboard.api.impl.BlackboardImpl;
import com.blackboard.api.impl.MessageQueueImpl;
import com.blackboard.constant.MQKeys;

import java.util.concurrent.CountDownLatch;

public class RegistryAgent {
    private static final String ENTITY_CAR = "CAR";
    private static final String ENTITY_KNOWLEDGE_SOURCE = "KNOWLEDGE_SOURCE";

    private final Blackboard board;
    private final MessageQueue mq;

    public RegistryAgent(Blackboard board, MessageQueue mq) {
        this.board = board;
        this.mq = mq;
    }

    public void start() {
        mq.connect();
        mq.subscribeRegistry(this::handleMessageSafely);
        System.out.println("RegistryAgent started.");
    }

    private void handleMessageSafely(String messageJson) {
        try {
            handleMessage(messageJson);
        } catch (Exception e) {
            e.printStackTrace();
            board.addLogEntry("ERROR: Registry failed: " + e.getMessage());
        }
    }

    private void handleMessage(String messageJson) {
        JSONObject msg = JSON.parseObject(messageJson);
        if (msg == null) {
            return;
        }

        String cmd = msg.getString("cmd");
        JSONObject data = getDataObject(msg);
        if (MQKeys.CMD_REGISTER.equals(cmd)) {
            handleRegister(data);
        } else if (MQKeys.CMD_HEARTBEAT.equals(cmd)) {
            handleHeartbeat(data);
        }
    }

    private void handleRegister(JSONObject data) {
        String entityType = data.getString("entityType");
        if (ENTITY_CAR.equalsIgnoreCase(entityType)) {
            String carId = data.getString("carId");
            int row = data.getIntValue("row");
            int col = data.getIntValue("col");
            String status = data.getString("status");
            board.registerCarInfo(carId, row, col, status);
            mq.declareCarQueue(carId);
            board.addLogEntry("INFO: Registry registered car: " + carId);
            return;
        }

        if (ENTITY_KNOWLEDGE_SOURCE.equalsIgnoreCase(entityType)) {
            String agentId = data.getString("agentId");
            String type = data.getString("type");
            String status = data.getString("status");
            board.registerKnowledgeSource(agentId, type, status);
            board.addLogEntry("INFO: Registry registered knowledge source: " + agentId);
        }
    }

    private void handleHeartbeat(JSONObject data) {
        String entityType = data.getString("entityType");
        if (ENTITY_CAR.equalsIgnoreCase(entityType)) {
            board.heartbeatCar(data.getString("carId"), data.getString("status"));
            return;
        }

        if (ENTITY_KNOWLEDGE_SOURCE.equalsIgnoreCase(entityType)) {
            board.heartbeatKnowledgeSource(data.getString("agentId"), data.getString("status"));
        }
    }

    private JSONObject getDataObject(JSONObject msg) {
        Object raw = msg.get("data");
        if (raw == null) {
            return new JSONObject();
        }
        if (raw instanceof JSONObject) {
            return (JSONObject) raw;
        }
        return JSON.parseObject(JSON.toJSONString(raw));
    }

    public static void main(String[] args) throws InterruptedException {
        String redisHost = env("REDIS_HOST", "localhost");
        int redisPort = Integer.parseInt(env("REDIS_PORT", "6379"));
        String rabbitHost = env("RABBITMQ_HOST", "localhost");
        int rabbitPort = Integer.parseInt(env("RABBITMQ_PORT", "5672"));

        Blackboard board = new BlackboardImpl(redisHost, redisPort);
        MessageQueue mq = new MessageQueueImpl(rabbitHost, rabbitPort);
        new RegistryAgent(board, mq).start();
        new CountDownLatch(1).await();
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
