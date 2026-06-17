package com.blackboard.display;

import com.blackboard.api.Blackboard;
import com.blackboard.api.MessageQueue;
import com.blackboard.api.impl.BlackboardImpl;
import com.blackboard.api.impl.MessageQueueImpl;
import com.blackboard.display.db.DBConnection;
import com.blackboard.display.obstacle.ObstacleManagerAgent;
import com.blackboard.display.user.UserManagerAgent;
import com.blackboard.display.websocket.SimWebSocketServer;
import com.blackboard.display.websocket.WebSocketBridge;
import com.blackboard.util.SimpleBridge;

/**
 * DisplayApplication —— 人4 主启动类
 * <p>
 * 职责：
 * <ul>
 *   <li>连接 Redis 黑板（读全量仿真状态）</li>
 *   <li>连接 SQL Server（UserManager 持久化）</li>
 *   <li>启动 WebSocket 服务器（端口 8887）</li>
 *   <li>订阅 MQ UpdateView 广播 → 推给前端</li>
 * </ul>
 * <p>
 * 不负责：Controller / Navigator / TargetPlanner 等仿真逻辑（人1/人2 负责）
 * </p>
 */
public class DisplayApplication {

    // ==================== 配置 ====================
    private static final String REDIS_HOST = "localhost";
    private static final int REDIS_PORT = 6379;
    private static final String MQ_HOST = "localhost";
    private static final int MQ_PORT = 5672;

    private static final String DB_URL = "jdbc:sqlserver://LAPTOP-LTTJ001U;databaseName=ExplorationDB;encrypt=false;trustServerCertificate=true";
    private static final String DB_USERNAME = "sa";
    private static final String DB_PASSWORD = "yxy450716" +
            "";

    public static void main(String[] args) throws Exception {

        System.out.println("============================================");
        System.out.println("  多机器人协作巡检仿真 - Display 模块启动");
        System.out.println("============================================");

        // ---- 1. Redis 黑板 ----
        Blackboard board;
        try {
            board = new BlackboardImpl(REDIS_HOST, REDIS_PORT);
            board.getMapWidth();
            System.out.println("[启动] Redis 黑板连接成功 (" + REDIS_HOST + ":" + REDIS_PORT + ")");
        } catch (Exception e) {
            System.err.println("[启动] Redis 连接失败: " + e.getMessage());
            System.err.println("[启动] 请确保 Redis 已启动后重试");
            return;
        }

        // ---- 2. RabbitMQ 消息队列 ----
        MessageQueue mq = null;
        try {
            mq = new MessageQueueImpl(MQ_HOST, MQ_PORT);
            mq.connect();
            System.out.println("[启动] RabbitMQ 连接成功 (" + MQ_HOST + ":" + MQ_PORT + ")");
        } catch (Exception e) {
            System.err.println("[启动] RabbitMQ 连接失败: " + e.getMessage());
            System.err.println("[启动] 仿真数据推送不可用，前端将使用演示模式");
            mq = null; // 标记为不可用
        }

        // ---- 3. SQL Server 数据库 ----
        try {
            DBConnection.init(DB_URL, DB_USERNAME, DB_PASSWORD);
            DBConnection.getConnection().close();
            System.out.println("[启动] SQL Server 连接成功");
        } catch (Exception e) {
            System.err.println("[启动] SQL Server 连接失败: " + e.getMessage());
            System.err.println("[启动] 用户管理将使用 Redis 降级方案");
        }

        // ---- 4. SimpleBridge 初始化（人4 简化接口）----
        SimpleBridge.init(board, mq);
        System.out.println("[启动] SimpleBridge 已就绪");

        // ---- 5. UserManagerAgent + ObstacleManagerAgent ----
        UserManagerAgent.init(board);
        ObstacleManagerAgent.init(board);
        System.out.println("[启动] UserManager 已就绪");
        System.out.println("[启动] ObstacleManager 已就绪");

        // ---- 6. WebSocket 服务器 ----
        SimWebSocketServer server = new SimWebSocketServer(8887);
        WebSocketBridge.setServer(server);

        // 订阅 MQ UpdateView 广播 → WebSocket 推给前端
        if (mq != null) {
            SimpleBridge.onRefresh(json -> server.broadcast(json));
            System.out.println("[启动] 已订阅 UpdateView 广播");
        }

        server.start();
        System.out.println("[启动] WebSocket 已启动 (端口 8887)");
        System.out.println("============================================");

        // ---- 主线程保持 ----
        while (true) {
            Thread.sleep(10000);
        }
    }
}