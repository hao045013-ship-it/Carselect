package com.blackboard.display.obstacle;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.blackboard.api.Blackboard;

/**
 * ObstacleManagerAgent —— 障碍物管理知识源（人4）
 * <p>
 * 职责：接收前端障碍物命令，直接写入 Redis 黑板。
 * <ul>
 *   <li>手动设置/移除单个障碍物</li>
 *   <li>按密度随机生成障碍物</li>
 *   <li>清除全部障碍物</li>
 *   <li>操作后推送全量状态给前端刷新</li>
 * </ul>
 * <p>
 * 不走 MQ，直接写 Redis——因为 ObstacleManager 本身就是黑板写者。
 * </p>
 */
public class ObstacleManagerAgent {

    private static Blackboard board;

    private ObstacleManagerAgent() {}

    public static void init(Blackboard blackboard) {
        board = blackboard;
        System.out.println("[ObstacleManager] 初始化完成");
    }

    // ==================== 命令处理 ====================

    /**
     * 处理来自 WebSocket 的障碍物命令
     */
    public static String handleCommand(String commandJson) {
        try {
            JSONObject req = JSON.parseObject(commandJson);
            String cmd = req.getString("command");
            JSONObject data = req.getJSONObject("data");

            if (cmd == null) return error("缺少 command");

            return switch (cmd.toUpperCase()) {
                case "SET_OBSTACLE"      -> handleSetObstacle(data);
                case "RANDOM_OBSTACLE"   -> handleRandomObstacles(data);
                case "CLEAR_OBSTACLE"    -> handleClearAll();
                default                  -> error("ObstacleManager 不处理命令: " + cmd);
            };
        } catch (Exception e) {
            return error("处理失败: " + e.getMessage());
        }
    }

    // ==================== 业务方法 ====================

    /**
     * 手动设置/移除单个格子
     * data: { row, col, value: true=添加 / false=移除 }
     */
    private static String handleSetObstacle(JSONObject data) {
        if (data == null) return error("缺少坐标数据");

        int row = data.getIntValue("row", -1);
        int col = data.getIntValue("col", -1);
        boolean value = data.getBooleanValue("value", true);

        if (row < 0 || col < 0) return error("坐标无效");
        if (board == null) return error("黑板未初始化");

        // 直接写 Redis 静态障碍物
        board.setStaticBlock(row, col, value);

        JSONObject d = new JSONObject();
        d.put("row", row); d.put("col", col); d.put("value", value);
        return ok(value ? "已添加障碍物" : "已移除障碍物", d);
    }

    /**
     * 按密度随机生成障碍物
     * data: { density: 0~100 }
     */
    private static String handleRandomObstacles(JSONObject data) {
        if (board == null) return error("黑板未初始化");

        int density = data != null ? data.getIntValue("density", 25) : 25;
        double d = Math.min(100, Math.max(0, density)) / 100.0;

        board.randomStaticBlocks(d);

        JSONObject d2 = new JSONObject();
        d2.put("density", density);
        return ok("已按密度 " + density + "% 随机生成障碍物", d2);
    }

    /**
     * 清除全部障碍物
     */
    private static String handleClearAll() {
        if (board == null) return error("黑板未初始化");

        board.clearStaticBlocks();
        board.clearDynamicBlocks();

        return ok("已清除全部障碍物（含静态和动态）", null);
    }

    // ==================== 公开 API（供其他组件直接调用）====================

    public static void setObstacle(int row, int col, boolean value) {
        if (board != null) board.setStaticBlock(row, col, value);
    }

    public static void randomObstacles(int densityPercent) {
        if (board != null) board.randomStaticBlocks(densityPercent / 100.0);
    }

    public static void clearAll() {
        if (board != null) board.clearStaticBlocks();
    }

    // ==================== 响应格式 ====================

    private static String ok(String message, Object data) {
        JSONObject resp = new JSONObject();
        resp.put("success", true);
        resp.put("message", message);
        if (data != null) resp.put("data", data);
        return resp.toJSONString();
    }

    private static String error(String message) {
        JSONObject resp = new JSONObject();
        resp.put("success", false);
        resp.put("error", message);
        System.err.println("[ObstacleManager] " + message);
        return resp.toJSONString();
    }

}
