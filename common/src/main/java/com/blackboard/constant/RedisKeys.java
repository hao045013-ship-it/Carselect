package com.blackboard.constant;

public final class RedisKeys {

    public static final int DEFAULT_MAP_WIDTH = 30;
    public static final int DEFAULT_MAP_HEIGHT = 30;
    public static final int VISION_RANGE = 1;
    public static final int BLOCKED_TIMEOUT_TICKS = 2;

    public static final String MAP_VIEW = "mapView";
    public static final String STATIC_BLOCK = "staticBlock";
    public static final String DYNAMIC_BLOCK = "dynamicBlock";

    @Deprecated
    public static final String MAP_BLOCK = "mapBlock";

    public static final String REGISTRY_CARS = "registry:cars";
    public static final String CAR_LIST = "CarList";
    public static final String REGISTRY_KS = "registry:knowledgeSources";
    public static final String REGISTRY_CAR_INFO_PREFIX = "registry:car:";
    public static final String REGISTRY_KS_INFO_PREFIX = "registry:ks:";

    public static final String TASK_CONFIG = "TaskConfig";
    public static final String CURRENT_TICK = "CurrentTick";
    public static final String REPLAY_SNAPSHOTS = "replay:snapshots";
    public static final String STATS_REPORT = "statsReport";
    public static final String COVERAGE_HISTORY = "coverageHistory";
    public static final String EXPLORATION_LOG = "explorationLog";

    public static final String CONTROLLER_LEADER = "controller:leader";
    public static final String CONTROLLER_HEARTBEAT = "registry:controller:heartbeat";

    public static final String LOCK_PREFIX = "lock:";

    public static String lockKey(String name) {
        return LOCK_PREFIX + name;
    }

    public static String registryCarInfoKey(String carId) {
        return REGISTRY_CAR_INFO_PREFIX + carId;
    }

    public static String registryKnowledgeSourceInfoKey(String agentId) {
        return REGISTRY_KS_INFO_PREFIX + agentId;
    }

    public static String userNameKey(String userId) {
        return "user:" + userId + ":name";
    }

    public static String userPrefsKey(String userId) {
        return "user:" + userId + ":prefs";
    }

    public static String userHistoryKey(String userId) {
        return "user:" + userId + ":history";
    }

    public static final String USER_CURRENT_ID = "user:currentId";
    public static final String USER_ID_COUNTER = "user:idCounter";

    public static String positionKey(String carId) {
        return carId + ":Position";
    }

    public static String targetKey(String carId) {
        return carId + ":Target";
    }

    public static String routeListKey(String carId) {
        return carId + ":RouteList";
    }

    public static String statusKey(String carId) {
        return carId + ":Status";
    }

    public static String stepsKey(String carId) {
        return carId + ":Steps";
    }

    public static String blockedTickKey(String carId) {
        return carId + ":BlockedTick";
    }

    public static String blockedCountKey(String carId) {
        return carId + ":BlockedCount";
    }

    public static String routePlanCountKey(String carId) {
        return carId + ":RoutePlanCount";
    }

    public static String traceKey(String carId) {
        return carId + ":Trace";
    }

    public static int index(int row, int col, int mapWidth) {
        return row * mapWidth + col;
    }

    public static int index(int row, int col) {
        return row * DEFAULT_MAP_WIDTH + col;
    }

    private RedisKeys() {
    }
}
