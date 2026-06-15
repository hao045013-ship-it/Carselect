package com.blackboard.display.websocket;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.blackboard.display.obstacle.ObstacleManagerAgent;
import com.blackboard.display.user.UserManagerAgent;
import com.blackboard.util.SimpleBridge;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.Set;

/**
 * WebSocket 服务器 —— 前端与后端唯一的通信通道
 * <p>
 * 命令路由（人4 职责）：
 * <ul>
 *   <li>用户命令（LOGIN/SAVE_PREF 等）→ UserManagerAgent 直接处理</li>
 *   <li>仿真命令（START/PAUSE 等）→ 转发到 MQ ControllerCmd（人1 的 Controller 消费）</li>
 *   <li>障碍物命令（SET_OBSTACLE 等）→ 写 Redis 黑板</li>
 * </ul>
 * </p>
 */
public class SimWebSocketServer extends WebSocketServer {

    public SimWebSocketServer(int port) {
        super(new InetSocketAddress(port));
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("[WebSocket] 客户端连接：" + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        System.out.println("[WebSocket] 客户端断开");
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        System.out.println("[WebSocket] 收到: " + (message.length() > 80 ? message.substring(0, 80) + "..." : message));

        JSONObject req;
        try {
            req = JSON.parseObject(message);
        } catch (Exception e) {
            System.out.println("[WebSocket] JSON 解析失败");
            return;
        }

        String cmd = req.getString("command");
        JSONObject data = req.getJSONObject("data");
        if (cmd == null) return;

        // ====== 用户命令 → UserManagerAgent ======
        if (isUserCommand(cmd)) {
            String response = UserManagerAgent.handleCommand(message);
            conn.send(response);
            return;
        }

        // ====== 障碍物命令 → ObstacleManagerAgent（直接写 Redis）======
        if (isObstacleCommand(cmd)) {
            String response = ObstacleManagerAgent.handleCommand(message);
            conn.send(response);
            System.out.println("[WebSocket] 障碍物命令已处理: " + cmd);
            return;
        }

        // ====== 仿真命令 → 转发到 MQ (人1 Controller 消费) ======
        if (isSimCommand(cmd)) {
            try {
                if ("ADD_CAR".equalsIgnoreCase(cmd) && data != null) {
                    String carId = data.getString("carId");
                    int x = data.getIntValue("x", 0);
                    int y = data.getIntValue("y", 0);
                    SimpleBridge.addCar(carId, x, y);
                } else if ("REMOVE_CAR".equalsIgnoreCase(cmd) && data != null) {
                    // 通过 MQ 转发移除命令
                    SimpleBridge.sendCommand(cmd);
                } else {
                    SimpleBridge.sendCommand(cmd);
                }
                System.out.println("[WebSocket] 已转发命令到 MQ: " + cmd);
            } catch (Exception e) {
                System.err.println("[WebSocket] 命令转发失败: " + e.getMessage());
            }
            return;
        }

        System.out.println("[WebSocket] 未识别命令: " + cmd);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("[WebSocket] 错误: " + ex.getMessage());
    }

    @Override
    public void onStart() {
        System.out.println("[WebSocket] 服务器启动成功，端口: " + getPort());
    }

    // ==================== 命令分类 ====================

    private static final Set<String> USER_COMMANDS = Set.of(
            "LOGIN", "LOGOUT", "GET_PROFILE", "UPDATE_NICKNAME",
            "SAVE_PREF", "GET_PREFS", "ADD_HISTORY", "GET_HISTORY",
            "CHANGE_PASSWORD"
    );

    private static final Set<String> OBSTACLE_COMMANDS = Set.of(
            "SET_OBSTACLE", "RANDOM_OBSTACLE", "CLEAR_OBSTACLE"
    );

    private static final Set<String> SIM_COMMANDS = Set.of(
            "START", "PAUSE", "RESUME", "RESET", "SET_SPEED",
            "ADD_CAR", "REMOVE_CAR"
    );

    private boolean isUserCommand(String cmd)     { return USER_COMMANDS.contains(cmd.toUpperCase()); }
    private boolean isObstacleCommand(String cmd) { return OBSTACLE_COMMANDS.contains(cmd.toUpperCase()); }
    private boolean isSimCommand(String cmd)      { return SIM_COMMANDS.contains(cmd.toUpperCase()); }

    /** 向所有已连接客户端广播消息 */
    public void broadcast(String message) {
        for (WebSocket conn : getConnections()) {
            conn.send(message);
        }
    }
}