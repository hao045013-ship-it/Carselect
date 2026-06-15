package com.blackboard.util;

import com.blackboard.api.Blackboard;
import com.blackboard.api.MessageListener;
import com.blackboard.api.MessageQueue;
import com.blackboard.constant.RedisKeys;
import com.blackboard.model.SimState;
import com.blackboard.model.Position;
import java.util.*;
import java.util.function.Consumer;
import com.blackboard.constant.MQKeys;
/**
 * SimpleBridge —— 人4专用简化工具
 *
 * 封装了 MQ 和 Redis 的全部细节，人4只需要调这几个静态方法。
 * 使用前必须先调用 init() 传入 board 和 mq 实例。
 */
public class SimpleBridge {

    private static Blackboard board;
    private static MessageQueue mq;
    private static boolean initialized = false;

    public static void init(Blackboard board, MessageQueue mq) {
        SimpleBridge.board = board;
        SimpleBridge.mq = mq;
        initialized = true;
    }

    private static void checkInit() {
        if (!initialized) throw new IllegalStateException("SimpleBridge not initialized! Call init() first.");
    }

    /**
     * 订阅刷新通知，收到后自动读全量黑板数据，传给 callback
     */
    public static void onRefresh(Consumer<String> callback) {
        checkInit();
        mq.subscribeUpdateView(new MessageListener() {
            @Override
            public void onMessage(String message) {
                String fullState = readFullState();
                callback.accept(fullState);
            }
        });
    }

    /**
     * 手动读取全量黑板数据（返回 JSON 字符串，直接可推给前端）
     */
    public static String readFullState() {
        checkInit();
        SimState state = new SimState();

        // 基础信息
        state.setMapWidth(board.getMapWidth());
        state.setMapHeight(board.getMapHeight());
        state.setExploredPercent(board.getExploredPercent());
        // tick 暂时无法从黑板获取，可后续扩展 Blackboard.getCurrentTick()
        state.setTick(board.getCurrentTick());
        Map<String, String> config = board.getTaskConfig();
        if (config != null) {
            state.setStatus(config.get("taskStatus"));
        }

        // 障碍物和视野（boolean[] 直接放入，fastjson2 会自动序列化为数组）
        state.setStaticBlock(board.getFullStaticBlock());
        state.setDynamicBlock(board.getFullDynamicBlock());
        state.setMapView(board.getFullMapView());
        state.setStatsReport(board.getStatsReport());
        state.setCoverageHistory(board.getCoverageHistory());
        // 车辆信息
        List<String> carIds = board.getCarList();
        Map<String, SimState.CarInfo> cars = new HashMap<>();
        for (String carId : carIds) {
            SimState.CarInfo info = new SimState.CarInfo();
            info.setCarId(carId);

            // 位置
            Map<String, String> posMap = board.getPosition(carId);
            if (posMap != null && posMap.containsKey("x")) {
                int x = Integer.parseInt(posMap.get("x"));
                int y = Integer.parseInt(posMap.get("y"));
                info.setPosition(new Position(x, y));
            }

            // 目标
            Map<String, String> targetMap = board.getTarget(carId);
            if (targetMap != null && targetMap.containsKey("x")) {
                int tx = Integer.parseInt(targetMap.get("x"));
                int ty = Integer.parseInt(targetMap.get("y"));
                info.setTarget(new Position(tx, ty));
            }

            // 状态
            info.setStatus(board.getStatus(carId));
            info.setStepsWalked(board.getSteps(carId));

            // 路径列表（需要从 Redis 读取，这里简单处理：获取全部路线并解析为 Position 列表）
            List<String> routeJsonList = board.getRouteList(carId);
            List<Position> routePositions = new ArrayList<>();
            for (String json : routeJsonList) {
                routePositions.add(Position.fromJson(json));
            }
            info.setRouteList(routePositions);

            cars.put(carId, info);
        }
        state.setCars(cars);

        // 统计报告（可选）
        String stats = board.getStatsReport();
        if (stats != null && !stats.isEmpty()) {
            // 可以合并到 state 的额外字段，SimState 中没有 statsReport，可以忽略或扩展
        }

        return state.toJson();
    }

    // ==================== 地图尺寸 ====================
    /**
     * 设置地图尺寸（会清空所有已有数据，谨慎使用）
     */
    public static void setMapSize(int width, int height) {
        checkInit();
        Map<String, Object> data = new HashMap<>();
        data.put("mapWidth", width);
        data.put("mapHeight", height);
        mq.sendCommand(MQKeys.CMD_SET_CONFIG, data);
    }
    public static void setConfig(Map<String, Object> config) {
        checkInit();
        mq.sendCommand(MQKeys.CMD_SET_CONFIG, config);
    }
    /**
     * 设置障碍物（true=添加, false=删除）
     */
    public static void setObstacle(int row, int col, boolean value) {
        checkInit();
        Map<String, Object> data = new HashMap<>();
        data.put("row", row);
        data.put("col", col);
        data.put("value", value);
        mq.sendCommand(MQKeys.CMD_SET_OBSTACLE, data);}

    /**
     * 按密度百分比随机生成障碍物
     */
    public static void randomObstacles(int densityPercent) {
        checkInit();
        Map<String, Object> data = new HashMap<>();
        data.put("density", densityPercent);
        mq.sendCommand(MQKeys.CMD_RANDOM_OBSTACLE, data);
    }

    /**
     * 清空所有障碍物
     */
    public static void clearAllObstacles() {
        checkInit();
        mq.sendCommand(MQKeys.CMD_CLEAR_OBSTACLE, Collections.emptyMap());
    }

    /**
     * 发送控制命令（START / PAUSE / RESET）
     */
    public static void sendCommand(String command) {
        checkInit();
        mq.sendCommand(command, Collections.emptyMap());
    }

    public static void sendCommand(String command, Map<String, Object> data) {
        checkInit();
        mq.sendCommand(command, data == null ? Collections.emptyMap() : data);
    }

    /**
     * 获取回放快照列表
     */
    /*public static List<String> getReplaySnapshots() {
        checkInit();
        return board.getAllSnapshots();
    }
    */
    public static List<String> getCarTrace(String carId) {
        checkInit();
        return board.getTrace(carId);
    }
    /**
     * 获取日志
     */
    public static List<String> getLogs(int count) {
        checkInit();
        return board.getLogs(count);
    }

    /**
     * 用户登录（输入昵称，自动分配ID）
     */
    public static String login(String nickname) {
        checkInit();
        String userId = board.createUser(nickname);
        board.setCurrentUser(userId);
        return userId;
    }

    /**
     * 获取当前用户ID
     */
    public static String getCurrentUserId() {
        checkInit();
        return board.getCurrentUser();
    }

    /**
     * 获取用户昵称
     */
    public static String getCurrentUserNickname() {
        checkInit();
        String userId = board.getCurrentUser();
        if (userId == null) return null;
        return board.getUserNickname(userId);
    }

    /**
     * 保存偏好设置
     */
    public static void savePreference(String key, String value) {
        checkInit();
        String userId = board.getCurrentUser();
        if (userId != null) {
            board.setUserPref(userId, key, value);
        }
    }

    /**
     * 获取偏好设置
     */
    public static java.util.Map<String, String> getPreferences() {
        checkInit();
        String userId = board.getCurrentUser();
        if (userId == null) return null;
        return board.getUserPrefs(userId);
    }
    /**
     * 添加车
     */
    public static void addCar(String carId, int row, int col) {
        checkInit();
        mq.addCar(carId, row, col);
    }
    /**
     *上传地图
     */
    public static void loadMapFile(int[][] mapData, int mapWidth, int mapHeight) {
        checkInit();
        mq.loadMapFile(mapData, mapWidth, mapHeight);
    }
}
