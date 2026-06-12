package com.blackboard.constant;

/**
 * Redis Key 常量 —— 所有人统一使用，不硬编码
 */
public class RedisKeys {

    public static final int MAP_WIDTH = 30;
    public static final int MAP_HEIGHT = 30;
    public static final int VISION_RANGE = 1;      // 视野半径（3×3）
    public static final int BLOCKED_TIMEOUT_TICKS = 2;  // 受阻超时节拍数
    public static final int MAX_CARS = 5;

    // ========== Bitmap ==========
    public static final String MAP_VIEW = "mapView";        // 探索视野
    public static final String MAP_BLOCK = "mapBlock";      // 障碍物

    // ========== Hash / String / List per car ==========
    public static String positionKey(String carId)     { return carId + ":Position"; }
    public static String targetKey(String carId)       { return carId + ":Target"; }
    public static String routeListKey(String carId)    { return carId + ":RouteList"; }
    public static String statusKey(String carId)       { return carId + ":Status"; }
    public static String stepsKey(String carId)        { return carId + ":Steps"; }
    public static String blockedTickKey(String carId)  { return carId + ":BlockedTick"; }

    // ========== 系统级 Key ==========
    public static final String TASK_CONFIG = "TaskConfig";
    public static final String REGISTRY_CARS = "registry:cars";
    public static final String STATS_REPORT = "statsReport";
    public static final String REPLAY_SNAPSHOTS = "replay:snapshots";
    public static final String EXPLORATION_LOG = "explorationLog";
    public static final String LOCK_PREFIX = "lock:";

    // ========== User Key ==========
    public static String userNameKey(String userId)    { return "user:" + userId + ":name"; }
    public static String userPrefsKey(String userId)   { return "user:" + userId + ":prefs"; }
    public static String userHistoryKey(String userId) { return "user:" + userId + ":history"; }
    public static final String USER_CURRENT_ID = "user:currentId";
    public static final String USER_ID_COUNTER = "user:idCounter";

    // ========== 工具方法 ==========
    public static int index(int row, int col) {
        return row * MAP_WIDTH + col;
    }

    public static String lockKey(String carId) {
        return LOCK_PREFIX + carId;
    }
}