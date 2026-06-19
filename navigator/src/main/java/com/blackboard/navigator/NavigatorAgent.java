package com.blackboard.navigator;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.blackboard.api.Blackboard;
import com.blackboard.api.MessageQueue;
import com.blackboard.api.impl.BlackboardImpl;
import com.blackboard.api.impl.MessageQueueImpl;
import com.blackboard.constant.MQKeys;
import com.blackboard.model.Message;
import com.blackboard.model.Position;
import com.blackboard.model.RouteAlgorithm;
import com.blackboard.navigator.service.RoutePlannerService;
import com.blackboard.navigator.service.RoutePlanningResult;
import com.blackboard.navigator.util.NavigatorDataReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

/**
 * Navigator 知识源。
 *
 * <p>职责：监听 PLAN_ROUTE，读取当前位置/目标/其他小车当前位置/已探索障碍物，调用 BFS、A* 或 Weighted A*，
 * 将路径写入 CarID:RouteList，并向 Controller 回复 ROUTE_PLANNED。</p>
 *
 * <p>重要约束：本组件只读取已经被 mapView 标记为探索过的静态障碍物。未探索区域中的障碍物
 * 仍然保持未知，不会被路径规划提前使用。</p>
 */
public class NavigatorAgent {
    private static final Logger log = LoggerFactory.getLogger(NavigatorAgent.class);

    private final Blackboard board;
    private final MessageQueue mq;
    private final RoutePlannerService plannerService;
    private final NavigatorDataReader reader;
    private final String agentId = "navigator-" + UUID.randomUUID();

    public NavigatorAgent(Blackboard board, MessageQueue mq) {
        this.board = board;
        this.mq = mq;
        this.plannerService = new RoutePlannerService();
        this.reader = new NavigatorDataReader(board);
    }

    public void start() {
        mq.connect();
        registerKnowledgeSource();
        mq.subscribeNavigator(this::handleMessageSafely);
        log.info("NavigatorAgent started. Waiting for {} messages.", MQKeys.CMD_PLAN_ROUTE);
    }

    private void registerKnowledgeSource() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("entityType", "KNOWLEDGE_SOURCE");
        data.put("agentId", agentId);
        data.put("type", "NAVIGATOR");
        data.put("status", "ONLINE");
        mq.sendToQueue(MQKeys.REGISTRY_CMD, MQKeys.CMD_REGISTER, data);
    }

    private void handleMessageSafely(String rawMessage) {
        try {
            handleMessage(rawMessage);
        } catch (Exception e) {
            log.error("Navigator failed to handle message: {}", rawMessage, e);
            String carId = tryReadCarId(rawMessage);
            if (carId != null) {
                mq.replyRoutePlanned(carId, false, 0);
            }
        }
    }

    private void handleMessage(String rawMessage) {
        Message message = JSON.parseObject(rawMessage, Message.class);
        if (message == null || !MQKeys.CMD_PLAN_ROUTE.equals(message.getCmd())) {
            log.debug("Navigator ignored message: {}", rawMessage);
            return;
        }

        JSONObject data = JSON.parseObject(JSON.toJSONString(message.getData()));
        String carId = data.getString("carId");
        if (carId == null || carId.isBlank()) {
            throw new IllegalArgumentException("PLAN_ROUTE missing carId");
        }

        RouteAlgorithm algorithm = parseAlgorithm(data.getString("algorithm"));
        planAndReply(carId, algorithm);
    }

    public List<Position> planAndReply(String carId, RouteAlgorithm algorithm) {
        int width = board.getMapWidth();
        int height = board.getMapHeight();
        Position start = reader.readPosition(carId);
        Position target = reader.readTarget(carId);

        boolean targetOutOfBounds = target.getX() < 0 || target.getX() >= width || target.getY() < 0 || target.getY() >= height;
        if (targetOutOfBounds) {
            board.clearRoute(carId);
            mq.replyRoutePlanned(carId, false, 0);
            logPlanningMetrics(carId, algorithm, false, 0, 0, 0, 0, 0, 0, "target_out_of_bounds");
            return List.of();
        }

        Set<Position> otherCarPositions = reader.readOtherCarPositions(carId);
        Set<Position> knownStaticObstacles = reader.readKnownStaticObstacles(width, height);
        Set<Position> knownBlockedCells = new HashSet<>(knownStaticObstacles);
        knownBlockedCells.addAll(otherCarPositions);
        if (knownStaticObstacles.contains(target)) {
            board.clearRoute(carId);
            mq.replyRoutePlanned(carId, false, 0);
            logPlanningMetrics(carId, algorithm, false, 0, 0, 0,
                    otherCarPositions.size(), knownStaticObstacles.size(), knownBlockedCells.size(),
                    "target_known_static_obstacle");
            return List.of();
        }
        if (otherCarPositions.contains(target)) {
            board.clearRoute(carId);
            mq.replyRoutePlanned(carId, false, 0);
            logPlanningMetrics(carId, algorithm, false, 0, 0, 0,
                    otherCarPositions.size(), knownStaticObstacles.size(), knownBlockedCells.size(),
                    "target_occupied_by_other_car");
            return List.of();
        }

        RoutePlanningResult result = plannerService.planWithMetrics(algorithm, start, target, width, height, knownBlockedCells);
        List<Position> route = result.getRoute();

        replaceRoute(carId, route);
        board.incrementRoutePlanCount(carId);
        mq.replyRoutePlanned(carId, result.isFound(), route.size());
        logPlanningMetrics(carId,
                algorithm,
                result.isFound(),
                route.size(),
                result.getVisitedNodes(),
                result.getElapsedMillis(),
                otherCarPositions.size(),
                knownStaticObstacles.size(),
                knownBlockedCells.size(),
                "ok");
        log.info("Route planned for {}, algorithm={}, found={}, len={}, visited={}, elapsedMs={}, knownStaticObstacles={}, otherCars={}",
                carId, algorithm, result.isFound(), route.size(), result.getVisitedNodes(), result.getElapsedMillis(),
                knownStaticObstacles.size(), otherCarPositions.size());
        return route;
    }

    private void replaceRoute(String carId, List<Position> route) {
        board.clearRoute(carId);
        for (Position p : route) {
            // BlackboardImpl.pushRoute 使用 LPUSH；按路径顺序写入后，Car 使用 RPOP 可以得到下一步。
            board.pushRoute(carId, p.toJson());
        }
    }

    private void logPlanningMetrics(String carId,
                                    RouteAlgorithm algorithm,
                                    boolean found,
                                    int routeLength,
                                    int visitedNodes,
                                    long elapsedMillis,
                                    int dynamicObstacleCount,
                                    int knownStaticObstacleCount,
                                    int totalBlockedCellCount,
                                    String reason) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("type", "ROUTE_PLAN_METRICS");
        metrics.put("carId", carId);
        metrics.put("algorithm", algorithm.name());
        metrics.put("routeFound", found);
        metrics.put("routeLength", routeLength);
        metrics.put("visitedNodes", visitedNodes);
        metrics.put("elapsedMs", elapsedMillis);
        metrics.put("dynamicObstacleCount", dynamicObstacleCount);
        metrics.put("knownStaticObstacleCount", knownStaticObstacleCount);
        metrics.put("totalBlockedCellCount", totalBlockedCellCount);
        metrics.put("reason", reason);
        metrics.put("time", System.currentTimeMillis());
        try {
            board.addLogEntry(JSON.toJSONString(metrics));
        } catch (Exception e) {
            log.debug("Failed to write route metrics to explorationLog. This does not affect route planning.", e);
        }
    }

    private RouteAlgorithm parseAlgorithm(String raw) {
        if (raw == null || raw.isBlank()) {
            return RouteAlgorithm.BFS;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT)
                .replace("-", "_")
                .replace(" ", "_")
                .replace("*", "_STAR");
        if ("ASTAR".equals(normalized)) normalized = "A_STAR";
        if ("A__STAR".equals(normalized)) normalized = "A_STAR";
        if ("WEIGHTEDASTAR".equals(normalized) || "W_A_STAR".equals(normalized) || "WA_STAR".equals(normalized)) {
            normalized = "WEIGHTED_A_STAR";
        }
        return RouteAlgorithm.valueOf(normalized);
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
        new NavigatorAgent(board, mq).start();
        new CountDownLatch(1).await();
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
