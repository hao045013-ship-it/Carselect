package com.blackboard.car;

import com.blackboard.api.Blackboard;
import com.blackboard.api.MessageQueue;
import com.blackboard.api.impl.MessageQueueImpl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CarFleet 只负责启动 CarAgent 消费者。
 *
 * 注意：
 * 1. 不创建小车
 * 2. 不设置小车坐标
 * 3. 不修改小车状态
 * 4. 不直接移动小车
 *
 * 小车的创建、初始位置、状态、队列声明仍由 Controller / TaskConfigurator 完成。
 */
public class CarFleet {

    private final Blackboard board;
    private final String rabbitHost;
    private final int rabbitPort;

    private final Map<String, CarAgent> runningAgents = new HashMap<>();
    private final Map<String, MessageQueue> runningQueues = new HashMap<>();

    private volatile boolean running = false;
    private Thread watcherThread;

    public CarFleet(Blackboard board, String rabbitHost, int rabbitPort) {
        this.board = board;
        this.rabbitHost = rabbitHost;
        this.rabbitPort = rabbitPort;
    }

    public void start() {
        if (running) {
            return;
        }

        running = true;

        watcherThread = new Thread(this::watchLoop, "CarFleet-Watcher");
        watcherThread.setDaemon(false);
        watcherThread.start();

        System.out.println("[CarFleet] started.");
    }

    public void stop() {
        running = false;

        if (watcherThread != null) {
            watcherThread.interrupt();
        }

        for (MessageQueue mq : runningQueues.values()) {
            try {
                mq.close();
            } catch (Exception ignored) {
            }
        }

        runningAgents.clear();
        runningQueues.clear();

        System.out.println("[CarFleet] stopped.");
    }

    private void watchLoop() {
        while (running) {
            try {
                scanAndStartCars();
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                System.err.println("[CarFleet] scan failed: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private synchronized void scanAndStartCars() {
        List<String> carIds = board.getCarList();

        if (carIds == null || carIds.isEmpty()) {
            return;
        }

        for (String carId : carIds) {
            if (carId == null || carId.isBlank()) {
                continue;
            }

            if (runningAgents.containsKey(carId)) {
                continue;
            }

            startOneCarAgent(carId);
        }
    }

    private void startOneCarAgent(String carId) {
        MessageQueue mq = new MessageQueueImpl(rabbitHost, rabbitPort);
        mq.connect();

        /*
         * 注意：
         * 这里可以声明队列，但不等于创建小车。
         * queueDeclare 是幂等的，Controller 已声明过也没关系。
         * 真正的小车状态仍然来自 Redis Blackboard。
         */
        mq.declareCarQueue(carId);

        CarAgent agent = new CarAgent(carId, board, mq);
        agent.start();

        runningAgents.put(carId, agent);
        runningQueues.put(carId, mq);

        System.out.println("[CarFleet] CarAgent started: " + carId);
    }
}