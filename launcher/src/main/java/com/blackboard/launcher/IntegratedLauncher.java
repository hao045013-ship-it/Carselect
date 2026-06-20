package com.blackboard.launcher;

import com.blackboard.api.Blackboard;
import com.blackboard.api.MessageQueue;
import com.blackboard.api.impl.BlackboardImpl;
import com.blackboard.api.impl.MessageQueueImpl;
import com.blackboard.car.CarAgent;
import com.blackboard.controller.ControllerAgent;
import com.blackboard.display.DisplayApplication;
import com.blackboard.navigator.NavigatorAgent;
import com.blackboard.obstaclemanager.ObstacleManagerAgent;
import com.blackboard.registry.RegistryAgent;
import com.blackboard.targetplanner.TargetPlannerAgent;
import com.blackboard.taskconfigurator.TaskConfiguratorAgent;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * One-process launcher for local integration testing.
 *
 * <p>Start Redis and RabbitMQ first, then run this class. After the frontend sends SET_CONFIG,
 * TaskConfigurator creates cars in Redis and CarFleet automatically starts one CarAgent per car.</p>
 */
public class IntegratedLauncher {

    private static final String REDIS_HOST = env("REDIS_HOST", "localhost");
    private static final int REDIS_PORT = Integer.parseInt(env("REDIS_PORT", "6379"));
    private static final String RABBITMQ_HOST = env("RABBITMQ_HOST", "localhost");
    private static final int RABBITMQ_PORT = Integer.parseInt(env("RABBITMQ_PORT", "5672"));

    public static void main(String[] args) throws Exception {
        System.out.println("============================================");
        System.out.println("  Blackboard Integrated Launcher");
        System.out.println("============================================");
        System.out.println("Redis: " + REDIS_HOST + ":" + REDIS_PORT);
        System.out.println("RabbitMQ: " + RABBITMQ_HOST + ":" + RABBITMQ_PORT);

        Blackboard board = new BlackboardImpl(REDIS_HOST, REDIS_PORT);

        MessageQueue obstacleMq = newMq();
        obstacleMq.declareAllQueues(0);

        new RegistryAgent(board, newMq()).start();
        System.out.println("[OK] Registry started.");

        new ObstacleManagerAgent(board, obstacleMq).start();
        System.out.println("[OK] ObstacleManager started and base queues declared.");

        MessageQueue taskMq = newMq();
        new TaskConfiguratorAgent(board, taskMq).start();
        System.out.println("[OK] TaskConfigurator started.");

        new TargetPlannerAgent(board, new MessageQueueImpl(RABBITMQ_HOST, RABBITMQ_PORT)).start();
        System.out.println("[OK] TargetPlanner started.");

        new NavigatorAgent(board, new MessageQueueImpl(RABBITMQ_HOST, RABBITMQ_PORT)).start();
        System.out.println("[OK] Navigator started.");

        MessageQueue controllerMq = newMq();
        ControllerAgent controller = new ControllerAgent(board, controllerMq);
        controller.start();
        System.out.println("[OK] Controller started.");

        CarFleet carFleet = new CarFleet(board);
        carFleet.start();
        System.out.println("[OK] CarFleet started. Cars will be created automatically after SET_CONFIG.");

        startDisplayInBackground();
        System.out.println("[OK] DisplayApplication starting on WebSocket port 8887.");
        System.out.println("Open display/src/main/resources/static/index.html and press Ctrl+F5.");
        System.out.println("============================================");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            controller.stop();
            carFleet.stop();
            controllerMq.close();
            board.close();
        }));

        new CountDownLatch(1).await();
    }

    private static MessageQueue newMq() {
        MessageQueue mq = new MessageQueueImpl(RABBITMQ_HOST, RABBITMQ_PORT);
        mq.connect();
        return mq;
    }

    private static void startDisplayInBackground() {
        Thread displayThread = new Thread(() -> {
            try {
                DisplayApplication.main(new String[0]);
            } catch (Exception e) {
                System.err.println("[Display] failed to start: " + e.getMessage());
                e.printStackTrace();
            }
        }, "display-application");
        displayThread.setDaemon(false);
        displayThread.start();
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static final class CarFleet {
        private final Blackboard board;
        private final Set<String> startedCars = ConcurrentHashMap.newKeySet();
        private final Map<String, CarAgent> agents = new ConcurrentHashMap<>();
        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        private CarFleet(Blackboard board) {
            this.board = board;
        }

        private void start() {
            scheduler.scheduleAtFixedRate(this::syncCarsSafely, 0, 1, TimeUnit.SECONDS);
        }

        private void stop() {
            scheduler.shutdownNow();
            agents.values().forEach(CarAgent::stop);
        }

        private void syncCarsSafely() {
            try {
                List<String> carIds = board.getCarList();
                for (String carId : carIds) {
                    if (carId != null && !carId.isBlank() && startedCars.add(carId)) {
                        startOneCar(carId);
                    }
                }
            } catch (Exception e) {
                System.err.println("[CarFleet] sync failed: " + e.getMessage());
            }
        }

        private void startOneCar(String carId) {
            MessageQueue carMq = new MessageQueueImpl(RABBITMQ_HOST, RABBITMQ_PORT);
            carMq.connect();
            carMq.declareCarQueue(carId);
            CarAgent agent = new CarAgent(carId, board, carMq);
            agent.start();
            agents.put(carId, agent);
            System.out.println("[CarFleet] " + carId + " started.");
        }
    }
}
