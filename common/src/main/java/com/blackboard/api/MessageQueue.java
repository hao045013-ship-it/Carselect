package com.blackboard.api;

import java.util.Map;

/**
 * 消息队列接口 —— 所有 RabbitMQ 操作的抽象
 * 实现类由组长（人1）编写，其他人依赖此接口开发
 */
public interface MessageQueue {

    // ==================== 初始化 ====================
    void connect();
    void declareAllQueues(int carCount);
    void close();

    // ==================== Controller → 知识源（命令下发） ====================
    void sendTickMove(String carId);
    void assignTarget(String carId);
    void planRoute(String carId, String algorithm);
    void forwardConfig(Map<String, String> config);
    void forwardReset();

    // ==================== 知识源 → Controller（回复通知） ====================
    void replyTaskReady(int carCount, int mapWidth, int mapHeight);
    void replyTargetAssigned(String jsonArray);
    void replyRoutePlanned(String carId, boolean routeFound, int routeLength);
    void replyMoved(String carId, int x, int y);
    void replyBlocked(String carId, int x, int y, int blockedX, int blockedY);
    void replyRouteDone(String carId, int x, int y);

    // ==================== 广播 ====================
    void broadcastRefreshAll(long tick);

    // ==================== 订阅 ====================
    void subscribeController(MessageListener listener);
    void subscribeCar(String carId, MessageListener listener);
    void subscribeNavigator(MessageListener listener);
    void subscribeTargetPlanner(MessageListener listener);
    void subscribeTaskConfig(MessageListener listener);
    void subscribeUpdateView(MessageListener listener);
    void subscribeObstacle(MessageListener listener);
    void subscribeRegistry(MessageListener listener);

    // ==================== 工具方法 ====================
    void sendCommand(String cmd, String dataJson);
}