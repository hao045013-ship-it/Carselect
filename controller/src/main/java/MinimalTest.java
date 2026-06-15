import com.alibaba.fastjson2.JSON;
import com.blackboard.api.Blackboard;
import com.blackboard.api.MessageQueue;
import com.blackboard.api.impl.BlackboardImpl;
import com.blackboard.api.impl.MessageQueueImpl;
import com.blackboard.constant.MQKeys;
import com.blackboard.model.CarStatus;
import com.blackboard.model.Position;
import com.blackboard.util.SimpleBridge;

import java.util.HashMap;
import java.util.Map;

public class MinimalTest {
    private static final boolean RUN_CONTROLLER_TESTS = false;

    private static Blackboard board;
    private static MessageQueue mq;

    public static void main(String[] args) throws Exception {
        board = new BlackboardImpl("localhost", 6379);
        mq = new MessageQueueImpl("localhost", 5672);
        mq.connect();

        board.clearAll();

        testTaskConfigurator();
        testObstacleManager();
        testCarAgentManual();
        testBlockedScenario();

        if (RUN_CONTROLLER_TESTS) {
            testTaskConfiguratorWithController();
            testControllerAutoTick();
        }

        testReadFullState();

        System.out.println("MinimalTest finished.");
        Thread.sleep(5000);
        mq.close();
        board.close();
    }

    private static void testTaskConfigurator() {
        System.out.println("\n=== Test TaskConfigurator init ===");

        Map<String, Object> config = new HashMap<>();
        config.put("mapWidth", 20);
        config.put("mapHeight", 20);
        config.put("carCount", 2);
        config.put("obstacleDensity", 10);
        config.put("algorithm", "BFS");
        config.put("cars", new Object[]{
                Map.of("carId", "Car001", "row", 5, "col", 5),
                Map.of("carId", "Car002", "row", 10, "col", 10)
        });

        mq.sendToQueue(MQKeys.TASK_CONFIG_CMD, MQKeys.CMD_FORWARD_CONFIG, config);
        sleep(2000);

        System.out.println("TaskConfig hash: " + board.getTaskConfig());
        System.out.println("StaticBlock length: " + board.getFullStaticBlock().length);
        System.out.println("registry:cars: " + board.getCarList());
        System.out.println("Car001 position: " + board.getPosition("Car001"));
        System.out.println("Car001 status: " + board.getStatus("Car001"));
        System.out.println("Car001 trace: " + board.getTrace("Car001"));
    }

    private static void testObstacleManager() {
        System.out.println("\n=== Test ObstacleManager ===");

        int row = 5;
        int col = 6;
        Map<String, Object> setData = new HashMap<>();
        setData.put("row", row);
        setData.put("col", col);
        setData.put("value", true);

        mq.sendToQueue(MQKeys.OBSTACLE_CMD, MQKeys.CMD_SET_OBSTACLE, setData);
        sleep(500);
        System.out.println("After set: hasStaticBlock(row=5,col=6) = " + board.hasStaticBlock(row, col));
        System.out.println("Logs: " + board.getLogs(10));

        mq.sendToQueue(MQKeys.OBSTACLE_CMD, MQKeys.CMD_CLEAR_OBSTACLE, new HashMap<>());
        sleep(500);
        System.out.println("After clear: hasStaticBlock(row=5,col=6) = " + board.hasStaticBlock(row, col));
        System.out.println("Logs: " + board.getLogs(10));
    }

    private static void testCarAgentManual() {
        System.out.println("\n=== Test CarAgent manual move ===");

        String carId = "Car001";
        Map<String, String> pos = board.getPosition(carId);
        if (pos == null || pos.isEmpty()) {
            System.out.println("Car001 is not initialized.");
            return;
        }

        int startX = Integer.parseInt(pos.get("x"));
        int startY = Integer.parseInt(pos.get("y"));
        System.out.println("Car001 start position: (" + startX + "," + startY + ")");

        board.clearRoute(carId);
        board.pushRoute(carId, new Position(startX + 1, startY).toJson());
        board.pushRoute(carId, new Position(startX + 2, startY).toJson());
        board.pushRoute(carId, new Position(startX + 3, startY).toJson());

        board.setStatus(carId, CarStatus.READY.name());
        mq.sendTickMove(carId);
        sleep(1000);

        Map<String, String> newPos = board.getPosition(carId);
        System.out.println("Position after move: (" + newPos.get("x") + "," + newPos.get("y") + ")");
        System.out.println("Car001 status: " + board.getStatus(carId));
        System.out.println("Car001 trace: " + board.getTrace(carId));
        System.out.println("dynamicBlock old(row=" + startY + ",col=" + startX + ") = "
                + board.hasDynamicBlock(startY, startX));
        System.out.println("dynamicBlock new(row=" + newPos.get("y") + ",col=" + newPos.get("x") + ") = "
                + board.hasDynamicBlock(Integer.parseInt(newPos.get("y")), Integer.parseInt(newPos.get("x"))));
    }

    private static void testBlockedScenario() {
        System.out.println("\n=== Test blocked scenario ===");

        String carId = "Car001";
        int startRow = 5;
        int startCol = 5;
        int targetRow = 5;
        int targetCol = 6;

        board.setPosition(carId, startRow, startCol);
        board.setDynamicBlock(startRow, startCol, true);
        board.setDynamicBlock(targetRow, targetCol, false);
        board.clearRoute(carId);
        board.setStatus(carId, CarStatus.IDLE.name());

        if (!board.hasStaticBlock(targetRow, targetCol)) {
            Map<String, Object> setData = new HashMap<>();
            setData.put("row", targetRow);
            setData.put("col", targetCol);
            setData.put("value", true);
            mq.sendToQueue(MQKeys.OBSTACLE_CMD, MQKeys.CMD_SET_OBSTACLE, setData);
            sleep(500);
        }
        System.out.println("Obstacle ensured at row=" + targetRow + ", col=" + targetCol);

        board.pushRoute(carId, new Position(targetCol, targetRow).toJson());
        board.setStatus(carId, CarStatus.READY.name());
        mq.sendTickMove(carId);
        sleep(1000);

        Map<String, String> pos = board.getPosition(carId);
        System.out.println("Position after blocked move: (" + pos.get("x") + "," + pos.get("y") + ")");
        System.out.println("Car001 status: " + board.getStatus(carId));
        System.out.println("Car001 route length: " + board.getRouteLength(carId));
        System.out.println("Blocked count: " + board.getBlockedCount(carId));
    }

    private static void testTaskConfiguratorWithController() {
        System.out.println("\n=== Test SET_CONFIG through Controller ===");

        Map<String, Object> config = new HashMap<>();
        config.put("mapWidth", 20);
        config.put("mapHeight", 20);
        config.put("carCount", 2);
        config.put("obstacleDensity", 10);
        config.put("algorithm", "BFS");
        config.put("cars", new Object[]{
                Map.of("carId", "Car001", "row", 5, "col", 5),
                Map.of("carId", "Car002", "row", 10, "col", 10)
        });

        mq.sendCommand(MQKeys.CMD_SET_CONFIG, config);
        sleep(2000);

        System.out.println("TaskConfig: " + board.getTaskConfig());
        System.out.println("Car001 position: " + board.getPosition("Car001"));
    }

    private static void testControllerAutoTick() {
        System.out.println("\n=== Test Controller auto tick ===");

        String carId = "Car001";
        board.setPosition(carId, 5, 5);
        board.setDynamicBlock(5, 5, true);
        board.setDynamicBlock(5, 6, false);
        board.setStaticBlock(5, 6, false);
        board.setStaticBlock(5, 7, false);
        board.clearRoute(carId);
        board.pushRoute(carId, new Position(6, 5).toJson());
        board.pushRoute(carId, new Position(7, 5).toJson());
        board.setStatus(carId, CarStatus.READY.name());

        sleep(5000);

        Map<String, String> pos = board.getPosition(carId);
        System.out.println("Position after 5 seconds: (" + pos.get("x") + "," + pos.get("y") + ")");
    }

    private static void testReadFullState() {
        System.out.println("\n=== Test readFullState ===");

        SimpleBridge.init(board, mq);
        String stateJson = SimpleBridge.readFullState();
        System.out.println("Full state JSON:\n" + stateJson);

        var obj = JSON.parseObject(stateJson);
        System.out.println("mapWidth: " + obj.getInteger("mapWidth"));
        System.out.println("cars: " + obj.getJSONObject("cars").keySet());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
