package com.blackboard.constant;

public class MQKeys {

    public static final String EXCHANGE_UPDATE_VIEW = "UpdateView";

    public static String carQueue(String carId) {
        return "Car_" + carId;
    }
    /*新增*/
    public static final String CMD_LOAD_MAP_FILE = "LOAD_MAP_FILE";

    public static final String NAVIGATOR_CMD = "NavigatorCmd";
    public static final String TARGET_PLANNER_CMD = "TargetPlannerCmd";
    public static final String TASK_CONFIG_CMD = "TaskConfigCmd";
    public static final String CONTROLLER_CMD = "ControllerCmd";
    public static final String OBSTACLE_CMD = "ObstacleCmd";
    public static final String REGISTRY_CMD = "RegistryCmd";
    public static final String STATS_CMD = "StatsCmd";
    public static final String REPLAY_CMD = "ReplayCmd";

    public static final String CMD_SET_CONFIG = "SET_CONFIG";
    public static final String CMD_FORWARD_CONFIG = "FORWARD_CONFIG";
    public static final String CMD_FORWARD_RESET = "FORWARD_RESET";

    public static final String CMD_TASK_READY = "TASK_READY";

    public static final String CMD_ASSIGN_TARGET = "ASSIGN_TARGET";
    public static final String CMD_TARGET_ASSIGNED = "TARGET_ASSIGNED";

    public static final String CMD_PLAN_ROUTE = "PLAN_ROUTE";
    public static final String CMD_ROUTE_PLANNED = "ROUTE_PLANNED";

    public static final String CMD_TICK_MOVE = "TICK_MOVE";
    public static final String CMD_MOVED = "MOVED";
    public static final String CMD_BLOCKED = "BLOCKED";
    public static final String CMD_ROUTE_DONE = "ROUTE_DONE";

    public static final String CMD_REFRESH_ALL = "REFRESH_ALL";

    public static final String CMD_SET_OBSTACLE = "SET_OBSTACLE";
    public static final String CMD_RANDOM_OBSTACLE = "RANDOM_OBSTACLE";
    public static final String CMD_CLEAR_OBSTACLE = "CLEAR_OBSTACLE";

    public static final String CMD_ADD_CAR = "ADD_CAR";

    public static final String CMD_RECORD_TRACE = "RECORD_TRACE";
    public static final String CMD_COLLECT_STATS = "COLLECT_STATS";

    public static final String CMD_REGISTER = "REGISTER";
    public static final String CMD_HEARTBEAT = "HEARTBEAT";

    public static final String CMD_START = "START";
    public static final String CMD_PAUSE = "PAUSE";
    public static final String CMD_RESUME = "RESUME";
    public static final String CMD_RESET = "RESET";

    private MQKeys() {
    }
}