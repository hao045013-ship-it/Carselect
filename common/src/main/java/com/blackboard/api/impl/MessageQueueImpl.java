package com.blackboard.api.impl;

import com.alibaba.fastjson2.JSON;
import com.blackboard.api.MessageListener;
import com.blackboard.api.MessageQueue;
import com.blackboard.constant.MQKeys;
import com.blackboard.model.Message;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;

import java.util.*;

/**
 * 消息队列实现类 —— 封装所有 RabbitMQ 操作
 *
 * 修改说明：
 * 1. 引入 fastjson2 和 Message 类，使用 buildMessage(cmd, Object data) 统一构建 JSON
 * 2. 所有发送消息的方法改为传入 Map 或简单对象作为 data，不再手动拼接 JSON 字符串
 * 3. 提高代码可读性、可维护性，避免手动拼接导致的转义错误
 *
 * 人1开发，其他人通过 MessageQueue 接口使用
 */
public class MessageQueueImpl implements MessageQueue {

    private final String host;
    private final int port;
    private Connection connection;
    private Channel channel;
    private int lastDeclaredCarCount = 0;

    public MessageQueueImpl(String host, int port) {
        this.host = host;
        this.port = port;
    }

    @Override
    public void connect() {
        try {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(host);
            factory.setPort(port);
            connection = factory.newConnection();
            channel = connection.createChannel();
            System.out.println("Connected to RabbitMQ at " + host + ":" + port);
        } catch (Exception e) {
            throw new RuntimeException("Failed to connect to RabbitMQ", e);
        }
    }

    @Override
    public void declareAllQueues(int carCount) {
        try {
            lastDeclaredCarCount = Math.max(lastDeclaredCarCount, carCount);
            // 声明 Fanout 交换机
            channel.exchangeDeclare(MQKeys.EXCHANGE_UPDATE_VIEW, "fanout", true);

            // 声明各个知识源命令队列
            channel.queueDeclare(MQKeys.NAVIGATOR_CMD, true, false, false, null);
            channel.queueDeclare(MQKeys.TARGET_PLANNER_CMD, true, false, false, null);
            channel.queueDeclare(MQKeys.TASK_CONFIG_CMD, true, false, false, null);
            channel.queueDeclare(MQKeys.CONTROLLER_CMD, true, false, false, null);
            channel.queueDeclare(MQKeys.OBSTACLE_CMD, true, false, false, null);
            channel.queueDeclare(MQKeys.REGISTRY_CMD, true, false, false, null);

            // 声明每辆小车的私有队列
            for (int i = 0; i < carCount; i++) {
                String carId = "Car" + String.format("%03d", i + 1);
                channel.queueDeclare(MQKeys.carQueue(carId), true, false, false, null);
            }

            System.out.println("All queues declared.");
        } catch (Exception e) {
            throw new RuntimeException("Failed to declare queues", e);
        }
    }

    @Override
    public void close() {
        try {
            if (channel != null) channel.close();
            if (connection != null) connection.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ==================== 通用发布 ====================
    private void publish(String routingKey, String message) {
        try {
            ensureOpenChannel();
            channel.basicPublish("", routingKey, null, message.getBytes("UTF-8"));
        } catch (Exception e) {
            try {
                reconnect();
                channel.basicPublish("", routingKey, null, message.getBytes("UTF-8"));
            } catch (Exception retryError) {
                throw new RuntimeException("Failed to publish message to " + routingKey, retryError);
            }
        }
    }

    private void publishToExchange(String exchange, String message) {
        try {
            ensureOpenChannel();
            channel.basicPublish(exchange, "", null, message.getBytes("UTF-8"));
        } catch (Exception e) {
            try {
                reconnect();
                channel.basicPublish(exchange, "", null, message.getBytes("UTF-8"));
            } catch (Exception retryError) {
                throw new RuntimeException("Failed to publish to exchange " + exchange, retryError);
            }
        }
    }

    private synchronized void ensureOpenChannel() {
        if (connection == null || channel == null || !connection.isOpen() || !channel.isOpen()) {
            reconnect();
        }
    }

    private synchronized void reconnect() {
        closeQuietly();
        connect();
        declareAllQueues(lastDeclaredCarCount);
    }

    private void closeQuietly() {
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
        } catch (Exception ignored) {
        }
        try {
            if (connection != null && connection.isOpen()) {
                connection.close();
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 构建消息 JSON
     * @param cmd  命令类型
     * @param data 数据对象（可以是 Map、POJO、String 等）
     * @return 序列化后的 JSON 字符串
     */
    private String buildMessage(String cmd, Object data) {
        return JSON.toJSONString(new Message(cmd, data));
    }

    // ==================== Controller → 知识源 ====================

    @Override
    public void sendTickMove(String carId) {
        // 没有额外数据，使用空 Map
        publish(MQKeys.carQueue(carId), buildMessage(MQKeys.CMD_TICK_MOVE, Collections.emptyMap()));
    }

    @Override
    public void assignTarget(String carId) {
        Map<String, Object> data = new HashMap<>();
        data.put("carId", carId);
        publish(MQKeys.TARGET_PLANNER_CMD, buildMessage(MQKeys.CMD_ASSIGN_TARGET, data));
    }

    //新增
    @Override
    public void assignTargets(List<String> carIds) {
        Map<String, Object> data = new HashMap<>();
        data.put("carIds", carIds == null ? Collections.emptyList() : new ArrayList<>(carIds));
        publish(MQKeys.TARGET_PLANNER_CMD, buildMessage(MQKeys.CMD_ASSIGN_TARGETS, data));
    }

    @Override
    public void planRoute(String carId, String algorithm) {
        Map<String, Object> data = new HashMap<>();
        data.put("carId", carId);
        data.put("algorithm", algorithm);
        publish(MQKeys.NAVIGATOR_CMD, buildMessage(MQKeys.CMD_PLAN_ROUTE, data));
    }

    @Override
    public void forwardConfig(Map<String, String> config) {
        // config 本身就是一个 Map，可以直接作为 data
        publish(MQKeys.TASK_CONFIG_CMD, buildMessage(MQKeys.CMD_FORWARD_CONFIG, config));
    }

    @Override
    public void forwardReset() {
        // 无数据，使用空 Map
        publish(MQKeys.TASK_CONFIG_CMD, buildMessage(MQKeys.CMD_FORWARD_RESET, Collections.emptyMap()));
    }

    // ==================== 知识源 → Controller ====================

    @Override
    public void replyTaskReady(int carCount, int mapWidth, int mapHeight) {
        Map<String, Object> data = new HashMap<>();
        data.put("carCount", carCount);
        data.put("mapWidth", mapWidth);
        data.put("mapHeight", mapHeight);
        publish(MQKeys.CONTROLLER_CMD, buildMessage(MQKeys.CMD_TASK_READY, data));
    }

    @Override
    public void replyTargetAssigned(String jsonArray) {
        // jsonArray 是已经序列化好的 JSON 数组字符串，直接作为 data
        Object data = jsonArray;
        if (jsonArray != null) {
            String trimmed = jsonArray.trim();
            if (trimmed.startsWith("[")) {
                Map<String, Object> wrapped = new HashMap<>();
                wrapped.put("assignedCars", JSON.parseArray(trimmed));
                data = wrapped;
            } else if (trimmed.startsWith("{")) {
                data = JSON.parseObject(trimmed);
            }
        }
        publish(MQKeys.CONTROLLER_CMD, buildMessage(MQKeys.CMD_TARGET_ASSIGNED, data));
    }

    @Override
    public void replyRoutePlanned(String carId, boolean routeFound, int routeLength) {
        Map<String, Object> data = new HashMap<>();
        data.put("carId", carId);
        data.put("routeFound", routeFound);
        data.put("routeLength", routeLength);
        publish(MQKeys.CONTROLLER_CMD, buildMessage(MQKeys.CMD_ROUTE_PLANNED, data));
    }

    @Override
    public void replyMoved(String carId, int x, int y) {
        Map<String, Object> data = new HashMap<>();
        data.put("carId", carId);
        data.put("x", x);
        data.put("y", y);
        publish(MQKeys.CONTROLLER_CMD, buildMessage(MQKeys.CMD_MOVED, data));
    }

    @Override
    public void replyBlocked(String carId, int x, int y, int blockedX, int blockedY) {
        Map<String, Object> data = new HashMap<>();
        data.put("carId", carId);
        data.put("x", x);
        data.put("y", y);
        data.put("blockedX", blockedX);
        data.put("blockedY", blockedY);
        publish(MQKeys.CONTROLLER_CMD, buildMessage(MQKeys.CMD_BLOCKED, data));
    }

    @Override
    public void replyRouteDone(String carId, int x, int y) {
        Map<String, Object> data = new HashMap<>();
        data.put("carId", carId);
        data.put("x", x);
        data.put("y", y);
        publish(MQKeys.CONTROLLER_CMD, buildMessage(MQKeys.CMD_ROUTE_DONE, data));
    }

    // ==================== 广播 ====================

    @Override
    public void broadcastEvent(String cmd, Map<String, Object> data) {
        if (data == null) {
            data = Collections.emptyMap();
        }
        publishToExchange(MQKeys.EXCHANGE_UPDATE_VIEW, buildMessage(cmd, data));
    }

    @Override
    public void broadcastRefreshAll(long tick) {
        Map<String, Object> data = new HashMap<>();
        data.put("tick", tick);
        broadcastEvent(MQKeys.CMD_REFRESH_ALL, data);
    }

    // ==================== 订阅 ====================

    @Override
    public void subscribeController(MessageListener listener) {
        subscribe(MQKeys.CONTROLLER_CMD, listener);
    }

    @Override
    public void subscribeCar(String carId, MessageListener listener) {
        subscribe(MQKeys.carQueue(carId), listener);
    }

    @Override
    public void subscribeNavigator(MessageListener listener) {
        subscribe(MQKeys.NAVIGATOR_CMD, listener);
    }

    @Override
    public void subscribeTargetPlanner(MessageListener listener) {
        subscribe(MQKeys.TARGET_PLANNER_CMD, listener);
    }

    @Override
    public void subscribeTaskConfig(MessageListener listener) {
        subscribe(MQKeys.TASK_CONFIG_CMD, listener);
    }

    //有改动
    @Override
    public void subscribeUpdateView(MessageListener listener) {
        try {
            //String queueName = channel.queueDeclare().getQueue();
            // 改动说明：传空字符串让 RabbitMQ 生成合法队列名（不带 amq. 前缀）
            String queueName = channel.queueDeclare("", false, true, true, null).getQueue();
            channel.queueBind(queueName, MQKeys.EXCHANGE_UPDATE_VIEW, "");
            subscribe(queueName, listener);
        } catch (Exception e) {
            throw new RuntimeException("Failed to subscribe UpdateView", e);
        }
    }

    @Override
    public void subscribeObstacle(MessageListener listener) {
        subscribe(MQKeys.OBSTACLE_CMD, listener);
    }

    @Override
    public void subscribeRegistry(MessageListener listener) {
        subscribe(MQKeys.REGISTRY_CMD, listener);
    }

    // 有改动==================== 通用订阅 ====================
    private void subscribe(String queueName, MessageListener listener) {
        try {
            // 确保队列存在（如果不存在则创建）
            //channel.queueDeclare(queueName, true, false, false, null);
            DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                String message = new String(delivery.getBody(), "UTF-8");
                listener.onMessage(message);
            };
            channel.basicConsume(queueName, true, deliverCallback, consumerTag -> {});
        } catch (Exception e) {
            throw new RuntimeException("Failed to subscribe to " + queueName, e);
        }
    }

    // ==================== 通用命令发送 ====================
    @Override
    public void sendCommand(String cmd, Map<String, Object> data) {
        publish(MQKeys.CONTROLLER_CMD, buildMessage(cmd, data));
    }

    @Override
    public void declareCarQueue(String carId) {
        try {
            channel.queueDeclare(MQKeys.carQueue(carId), true, false, false, null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to declare queue for car: " + carId, e);
        }
    }
    @Override
    public void addCar(String carId, int row, int col) {
        Map<String, Object> data = new HashMap<>();
        data.put("carId", carId);
        data.put("row", row);
        data.put("col", col);
        sendCommand(MQKeys.CMD_ADD_CAR, data);
    }
    @Override
    public void loadMapFile(int[][] mapData, int mapWidth, int mapHeight) {
        Map<String, Object> data = new HashMap<>();
        data.put("mapData", mapData);
        data.put("mapWidth", mapWidth);
        data.put("mapHeight", mapHeight);
        sendCommand(MQKeys.CMD_LOAD_MAP_FILE, data);
        //
    }
    // ==================== 通用队列转发 ====================
    @Override
    public void sendToQueue(String queueName, String cmd, Map<String, Object> data) {
        if (queueName == null || queueName.isBlank()) {
            throw new IllegalArgumentException("queueName cannot be null or blank");
        }

        if (cmd == null || cmd.isBlank()) {
            throw new IllegalArgumentException("cmd cannot be null or blank");
        }

        if (data == null) {
            data = Collections.emptyMap();
        }

        publish(queueName, buildMessage(cmd, data));
    }

    public void purgeSimulationCommandQueues(int carCount) {
        ensureOpenChannel();
        purgeQueue(MQKeys.NAVIGATOR_CMD);
        purgeQueue(MQKeys.TARGET_PLANNER_CMD);
        purgeQueue(MQKeys.OBSTACLE_CMD);
        for (int i = 1; i <= carCount; i++) {
            purgeQueue(MQKeys.carQueue("Car" + String.format("%03d", i)));
        }
    }

    private void purgeQueue(String queueName) {
        try {
            channel.queuePurge(queueName);
        } catch (Exception ignored) {
            // Queue may not exist yet; it will be declared before use.
        }
    }
}
