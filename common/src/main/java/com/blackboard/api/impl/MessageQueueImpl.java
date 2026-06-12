package com.blackboard.api.impl;

import com.blackboard.api.MessageListener;
import com.blackboard.api.MessageQueue;
import com.blackboard.constant.MQKeys;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;

import java.util.Map;

/**
 * 消息队列实现类 —— 封装所有 RabbitMQ 操作
 *
 * 人1开发，其他人通过 MessageQueue 接口使用
 */
public class MessageQueueImpl implements MessageQueue {

    private final String host;
    private final int port;
    private Connection connection;
    private Channel channel;

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
            channel.basicPublish("", routingKey, null, message.getBytes("UTF-8"));
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish message to " + routingKey, e);
        }
    }

    private void publishToExchange(String exchange, String message) {
        try {
            channel.basicPublish(exchange, "", null, message.getBytes("UTF-8"));
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish to exchange " + exchange, e);
        }
    }

    private String buildMessage(String cmd, String dataJson) {
        return "{\"cmd\":\"" + cmd + "\",\"data\":" + dataJson + ",\"timestamp\":" + System.currentTimeMillis() + "}";
    }

    // ==================== Controller → 知识源 ====================
    @Override
    public void sendTickMove(String carId) {
        publish(MQKeys.carQueue(carId), buildMessage(MQKeys.CMD_TICK_MOVE, "{}"));
    }

    @Override
    public void assignTarget(String carId) {
        publish(MQKeys.TARGET_PLANNER_CMD, buildMessage(MQKeys.CMD_ASSIGN_TARGET, "{\"carId\":\"" + carId + "\"}"));
    }

    @Override
    public void planRoute(String carId, String algorithm) {
        publish(MQKeys.NAVIGATOR_CMD, buildMessage(MQKeys.CMD_PLAN_ROUTE, "{\"carId\":\"" + carId + "\",\"algorithm\":\"" + algorithm + "\"}"));
    }

    @Override
    public void forwardConfig(Map<String, String> config) {
        StringBuilder sb = new StringBuilder("{");
        for (Map.Entry<String, String> e : config.entrySet()) {
            if (sb.length() > 1) sb.append(",");
            sb.append("\"").append(e.getKey()).append("\":\"").append(e.getValue()).append("\"");
        }
        sb.append("}");
        publish(MQKeys.TASK_CONFIG_CMD, buildMessage(MQKeys.CMD_FORWARD_CONFIG, sb.toString()));
    }

    @Override
    public void forwardReset() {
        publish(MQKeys.TASK_CONFIG_CMD, buildMessage(MQKeys.CMD_FORWARD_RESET, "{}"));
    }

    // ==================== 知识源 → Controller ====================
    @Override
    public void replyTaskReady(int carCount, int mapWidth, int mapHeight) {
        String data = "{\"carCount\":" + carCount + ",\"mapWidth\":" + mapWidth + ",\"mapHeight\":" + mapHeight + "}";
        publish(MQKeys.CONTROLLER_CMD, buildMessage(MQKeys.CMD_TASK_READY, data));
    }

    @Override
    public void replyTargetAssigned(String jsonArray) {
        publish(MQKeys.CONTROLLER_CMD, buildMessage(MQKeys.CMD_TARGET_ASSIGNED, jsonArray));
    }

    @Override
    public void replyRoutePlanned(String carId, boolean routeFound, int routeLength) {
        String data = "{\"carId\":\"" + carId + "\",\"routeFound\":" + routeFound + ",\"routeLength\":" + routeLength + "}";
        publish(MQKeys.CONTROLLER_CMD, buildMessage(MQKeys.CMD_ROUTE_PLANNED, data));
    }

    @Override
    public void replyMoved(String carId, int x, int y) {
        String data = "{\"carId\":\"" + carId + "\",\"x\":" + x + ",\"y\":" + y + "}";
        publish(MQKeys.CONTROLLER_CMD, buildMessage(MQKeys.CMD_MOVED, data));
    }

    @Override
    public void replyBlocked(String carId, int x, int y, int blockedX, int blockedY) {
        String data = "{\"carId\":\"" + carId + "\",\"x\":" + x + ",\"y\":" + y + ",\"blockedX\":" + blockedX + ",\"blockedY\":" + blockedY + "}";
        publish(MQKeys.CONTROLLER_CMD, buildMessage(MQKeys.CMD_BLOCKED, data));
    }

    @Override
    public void replyRouteDone(String carId, int x, int y) {
        String data = "{\"carId\":\"" + carId + "\",\"x\":" + x + ",\"y\":" + y + "}";
        publish(MQKeys.CONTROLLER_CMD, buildMessage(MQKeys.CMD_ROUTE_DONE, data));
    }

    // ==================== 广播 ====================
    @Override
    public void broadcastRefreshAll(long tick) {
        publishToExchange(MQKeys.EXCHANGE_UPDATE_VIEW, buildMessage(MQKeys.CMD_REFRESH_ALL, "{\"tick\":" + tick + "}"));
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

    @Override
    public void subscribeUpdateView(MessageListener listener) {
        try {
            // 每个订阅者声明自己的匿名队列，绑定到 Fanout 交换机
            String queueName = channel.queueDeclare().getQueue();
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

    // ==================== 通用订阅 ====================
    private void subscribe(String queueName, MessageListener listener) {
        try {
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
    public void sendCommand(String cmd, String dataJson) {
        publish(MQKeys.CONTROLLER_CMD, buildMessage(cmd, dataJson));
    }
}