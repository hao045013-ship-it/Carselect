package com.blackboard.constant;

/**
 * Redis Key 常量（合并自两个版本）
 * <ul>
 *   <li>支持静态/动态障碍物分离</li>
 *   <li>支持车辆详细统计（受阻次数、重规划次数、轨迹）</li>
 *   <li>支持回放快照、覆盖率历史、节拍计数器等功能</li>
 * </ul>
 */
public final class RedisKeys {

    // ========== 全局常量 ==========
    public static final int DEFAULT_MAP_WIDTH = 30;       // 默认地图宽度
    public static final int DEFAULT_MAP_HEIGHT = 30;      // 默认地图高度
    public static final int VISION_RANGE = 1;             // 视野半径（3x3）
    public static final int BLOCKED_TIMEOUT_TICKS = 2;    // 连续受阻超时节拍数
                // 最大车辆数（业务限制）

    // ========== 地图相关 Key ==========
    public static final String MAP_VIEW = "mapView";           // 探索视野（Bitmap）
    // 障碍物存储（B 引入的区分）
    public static final String STATIC_BLOCK = "staticBlock";   // 静态障碍物（固定不变）
    public static final String DYNAMIC_BLOCK = "dynamicBlock"; // 动态障碍物（临时/移动）
    // 兼容旧版：若使用混合障碍物仍可保留，建议逐步迁移至 STATIC/DYNAMIC
    @Deprecated
    public static final String MAP_BLOCK = "mapBlock";         // 旧版混合障碍物

    // ========== 车辆注册与集合 ==========
    public static final String REGISTRY_CARS = "registry:cars"; // 车辆注册表（A 风格）
    public static final String CAR_LIST = "CarList";            // 简单车辆列表（B 风格，可共存）

    // ========== 系统级 Key ==========
    public static final String TASK_CONFIG = "TaskConfig";           // 任务配置
    public static final String CURRENT_TICK = "CurrentTick";         // 当前节拍数（B 新增）
    public static final String REPLAY_SNAPSHOTS = "replay:snapshots"; // 回放快照列表（A 特有）
    public static final String STATS_REPORT = "statsReport";          // 统计报告
    public static final String COVERAGE_HISTORY = "coverageHistory";  // 覆盖率历史记录（B 新增）
    public static final String EXPLORATION_LOG = "explorationLog";    // 探索日志
    public static final String REGISTRY_KS = "registry:knowledgeSources"; // 知识源注册表（B 新增）

    // ========== 锁相关 ==========
    public static final String LOCK_PREFIX = "lock:";            // 锁 Key 前缀（A 提供）
    public static String lockKey(String name) {                 // 通用锁 Key（B 风格，参数更通用）
        return LOCK_PREFIX + name;
    }

    // ========== 用户相关 Key ==========
    public static final String USER_CURRENT_ID = "user:currentId";
    public static final String USER_ID_COUNTER = "user:idCounter";

    public static String userNameKey(String userId) {
        return "user:" + userId + ":name";
    }

    public static String userPrefsKey(String userId) {
        return "user:" + userId + ":prefs";
    }

    public static String userHistoryKey(String userId) {
        return "user:" + userId + ":history";
    }

    // ========== 每辆车的动态 Key（Hash / String / List） ==========
    // 基础信息（A 和 B 共有）
    public static String positionKey(String carId)     { return carId + ":Position"; }
    public static String targetKey(String carId)       { return carId + ":Target"; }
    public static String routeListKey(String carId)    { return carId + ":RouteList"; }
    public static String statusKey(String carId)       { return carId + ":Status"; }
    public static String stepsKey(String carId)        { return carId + ":Steps"; }
    public static String blockedTickKey(String carId)  { return carId + ":BlockedTick"; }  // 当前连续受阻节拍数

    // 扩展统计（B 新增，用于更细粒度的分析）
    public static String blockedCountKey(String carId)    { return carId + ":BlockedCount"; }    // 总受阻次数
    public static String routePlanCountKey(String carId)  { return carId + ":RoutePlanCount"; }  // 路径规划次数
    public static String traceKey(String carId)           { return carId + ":Trace"; }           // 移动轨迹（List of positions）

    // ========== 工具方法 ==========
    /**
     * 根据行列计算线性索引（需传入地图宽度）。
     * 推荐使用此方法，支持可变地图尺寸。
     */
    public static int index(int row, int col, int mapWidth) {
        return row * mapWidth + col;
    }

    /**
     * 使用默认地图宽度的便捷方法（兼容旧代码）。
     * 注意：若地图宽度非默认值，请使用 {@link #index(int, int, int)}。
     */
    public static int index(int row, int col) {
        return row * DEFAULT_MAP_WIDTH + col;
    }

    // 私有构造函数，防止实例化
    private RedisKeys() {
    }
}