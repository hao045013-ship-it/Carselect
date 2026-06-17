package com.blackboard.constant;

public class MQKeys {

    public static final String EXCHANGE_UPDATE_VIEW = "UpdateView";

    public static String carQueue(String carId) {
        return "Car_" + carId;
    }
    /*新增*/
    public static final String NAVIGATOR_CMD = "NavigatorCmd";
    public static final String TARGET_PLANNER_CMD = "TargetPlannerCmd";
    public static final String TASK_CONFIG_CMD = "TaskConfigCmd";
    public static final String CONTROLLER_CMD = "ControllerCmd";
    public static final String OBSTACLE_CMD = "ObstacleCmd";
    public static final String REGISTRY_CMD = "RegistryCmd";
    public static final String STATS_CMD = "StatsCmd";
    public static final String REPLAY_CMD = "ReplayCmd";
    public static final String USER_CMD = "UserCmd";

    // ==================== Task config / lifecycle commands ====================
    public static final String CMD_SET_CONFIG = "SET_CONFIG";
    public static final String CMD_FORWARD_CONFIG = "FORWARD_CONFIG";
    public static final String CMD_FORWARD_RESET = "FORWARD_RESET";
    public static final String CMD_LOAD_MAP_FILE = "LOAD_MAP_FILE";

    public static final String CMD_TASK_READY = "TASK_READY";
    public static final String CMD_TASK_FINISHED = "TASK_FINISHED";
    public static final String CMD_TASK_PAUSED = "TASK_PAUSED";
    public static final String CMD_TASK_RESUMED = "TASK_RESUMED";

    // ==================== Target assignment commands ====================
    public static final String CMD_ASSIGN_TARGET = "ASSIGN_TARGET";
    public static final String CMD_TARGET_ASSIGNED = "TARGET_ASSIGNED";
    public static final String CMD_ASSIGN_INSPECTION_TARGET = "ASSIGN_INSPECTION_TARGET";
    public static final String CMD_SET_INSPECTION_POINTS = "SET_INSPECTION_POINTS";
    public static final String CMD_INSPECTION_DONE = "INSPECTION_DONE";

    // ==================== Route planning commands ====================
    public static final String CMD_PLAN_ROUTE = "PLAN_ROUTE";
    public static final String CMD_ROUTE_PLANNED = "ROUTE_PLANNED";

    // ==================== Car movement / state commands ====================
    public static final String CMD_TICK_MOVE = "TICK_MOVE";
    public static final String CMD_MOVED = "MOVED";
    public static final String CMD_BLOCKED = "BLOCKED";
    public static final String CMD_ROUTE_DONE = "ROUTE_DONE";
    public static final String CMD_BATTERY_LOW = "BATTERY_LOW";
    public static final String CMD_CHARGE_REQUEST = "CHARGE_REQUEST";
    public static final String CMD_CHARGING = "CHARGING";
    public static final String CMD_CHARGE_DONE = "CHARGE_DONE";

    // ==================== View refresh commands ====================
    public static final String CMD_REFRESH_ALL = "REFRESH_ALL";

    // ==================== Obstacle commands ====================
    public static final String CMD_SET_OBSTACLE = "SET_OBSTACLE";
    public static final String CMD_RANDOM_OBSTACLE = "RANDOM_OBSTACLE";
    public static final String CMD_CLEAR_OBSTACLE = "CLEAR_OBSTACLE";

    // ==================== Car registry commands ====================
    public static final String CMD_ADD_CAR = "ADD_CAR";

    // ==================== Replay / trace commands ====================
    public static final String CMD_RECORD_TRACE = "RECORD_TRACE";
    public static final String CMD_SAVE_SNAPSHOT = "SAVE_SNAPSHOT";
    public static final String CMD_GET_REPLAY = "GET_REPLAY";
    public static final String CMD_REPLAY_READY = "REPLAY_READY";
    public static final String CMD_CLEAR_REPLAY = "CLEAR_REPLAY";

    // ==================== Stats commands ====================
    public static final String CMD_COLLECT_STATS = "COLLECT_STATS";
    public static final String CMD_GET_STATS = "GET_STATS";
    public static final String CMD_STATS_READY = "STATS_READY";

    // ==================== Knowledge source registry commands ====================
    public static final String CMD_REGISTER = "REGISTER";
    public static final String CMD_HEARTBEAT = "HEARTBEAT";

    // ==================== User commands ====================
    public static final String CMD_LOGIN = "LOGIN";
    public static final String CMD_LOGOUT = "LOGOUT";
    public static final String CMD_SAVE_PREF = "SAVE_PREF";
    public static final String CMD_GET_PREF = "GET_PREF";
    public static final String CMD_USER_READY = "USER_READY";

    // ==================== Log query commands ====================
    public static final String CMD_RECORD_LOG = "RECORD_LOG";
    public static final String CMD_GET_LOGS = "GET_LOGS";
    public static final String CMD_LOGS_READY = "LOGS_READY";

    // ==================== Control commands ====================
    public static final String CMD_START = "START";
    public static final String CMD_PAUSE = "PAUSE";
    public static final String CMD_RESUME = "RESUME";
    public static final String CMD_RESET = "RESET";
    //新增

    private MQKeys() {
    }
}
