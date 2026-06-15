package com.blackboard.display.websocket;

/**
 * WebSocketBridge —— 前端广播桥梁
 * <p>
 * 统一管理 WebSocket 服务器实例，供各组件调用 broadcast()。
 * </p>
 */
public class WebSocketBridge {

    private static SimWebSocketServer server;

    /** 由 DisplayApplication 启动时注入 */
    public static void setServer(SimWebSocketServer server) {
        WebSocketBridge.server = server;
    }

    /** 向后兼容：直接 init（demo 模式） */
    public static void init() {
        server = new SimWebSocketServer(8887);
        server.start();
    }

    /** 向所有已连接的前端客户端广播消息 */
    public static void broadcast(String msg) {
        if (server != null) {
            server.broadcast(msg);
        }
    }
}