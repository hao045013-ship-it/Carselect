package com.blackboard.constant;

/**
 * RabbitMQ 队列名常量 —— 所有人统一使用
 */
public class MQKeys {

    public static final String EXCHANGE_UPDATE_VIEW = "UpdateView";  // Fanout 广播

    // ========== 队列名 ==========
    public static String carQueue(String carId)      { return "Car_" + carId; }
    public static final String NAVIGATOR_CMD    = "NavigatorCmd";
    public static final String TARGET_PLANNER_CMD = "TargetPlannerCmd";
    public static final String TASK_CONFIG_CMD  = "TaskConfigCmd";
    public static final String CONTROLLER_CMD   = "ControllerCmd";
    public static final String OBSTACLE_CMD     = "ObstacleCmd";
    public static final String REGISTRY_CMD     = "RegistryCmd";
    public static final String REPLAY_CMD       = "ReplayCmd";

    // ========== 消息 cmd 常量 ==========
    public static final String CMD_TICK_MOVE         = "TICK_MOVE";
    public static final String CMD_ASSIGN_TARGET     = "ASSIGN_TARGET";
    public static final String CMD_PLAN_ROUTE        = "PLAN_ROUTE";
    public static final String CMD_FORWARD_CONFIG    = "FORWARD_CONFIG";
    public static final String CMD_FORWARD_RESET     = "FORWARD_RESET";
    public static final String CMD_TASK_READY        = "TASK_READY";
    public static final String CMD_TARGET_ASSIGNED   = "TARGET_ASSIGNED";
    public static final String CMD_ROUTE_PLANNED     = "ROUTE_PLANNED";
    public static final String CMD_MOVED             = "MOVED";
    public static final String CMD_BLOCKED           = "BLOCKED";
    public static final String CMD_ROUTE_DONE        = "ROUTE_DONE";
    public static final String CMD_REFRESH_ALL       = "REFRESH_ALL";
    public static final String CMD_SET_OBSTACLE      = "SET_OBSTACLE";
    public static final String CMD_REGISTER          = "REGISTER";
    public static final String CMD_HEARTBEAT         = "HEARTBEAT";
    public static final String CMD_START             = "START";
    public static final String CMD_PAUSE             = "PAUSE";
    public static final String CMD_RESET             = "RESET";
    public static final String CMD_SET_CONFIG        = "SET_CONFIG";

    // ========== 状态常量 ==========
    public static final String STATUS_IDLE           = "IDLE";
    public static final String STATUS_WAITING_ROUTE  = "WAITING_ROUTE";
    public static final String STATUS_READY          = "READY";
    public static final String STATUS_MOVING         = "MOVING";
    public static final String STATUS_BLOCKED        = "BLOCKED";
}