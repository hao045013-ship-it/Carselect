package com.blackboard.util;

import com.blackboard.api.Blackboard;
import com.blackboard.api.MessageListener;
import com.blackboard.api.MessageQueue;
import com.blackboard.constant.RedisKeys;

import java.util.List;
import java.util.function.Consumer;

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
        // 构建 SimState JSON
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"mapWidth\":").append(RedisKeys.MAP_WIDTH).append(",");
        sb.append("\"mapHeight\":").append(RedisKeys.MAP_HEIGHT).append(",");
        sb.append("\"exploredPercent\":").append(board.getExploredPercent()).append(",");
        sb.append("\"statsReport\":").append(board.getStatsReport() != null ? board.getStatsReport() : "{}");
        sb.append("}");
        return sb.toString();
    }

    /**
     * 设置障碍物（true=添加, false=删除）
     */
    public static void setObstacle(int row, int col, boolean value) {
        checkInit();
        board.setObstacle(row, col, value);
    }

    /**
     * 按密度百分比随机生成障碍物
     */
    public static void randomObstacles(int densityPercent) {
        checkInit();
        board.randomObstacles(densityPercent / 100.0);
    }

    /**
     * 清空所有障碍物
     */
    public static void clearAllObstacles() {
        checkInit();
        board.clearAllObstacles();
    }

    /**
     * 发送控制命令（START / PAUSE / RESET）
     */
    public static void sendCommand(String command) {
        checkInit();
        mq.sendCommand(command, "{}");
    }

    /**
     * 获取回放快照列表
     */
    public static List<String> getReplaySnapshots() {
        checkInit();
        return board.getAllSnapshots();
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
}