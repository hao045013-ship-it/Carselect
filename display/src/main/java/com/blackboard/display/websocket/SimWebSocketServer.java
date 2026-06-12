package com.blackboard.display.websocket;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;

public class SimWebSocketServer extends WebSocketServer {

    public SimWebSocketServer(int port) {

        super(new InetSocketAddress(port));

    }

    @Override
    public void onOpen(
            WebSocket conn,
            ClientHandshake handshake) {

        System.out.println(
                "客户端连接：" +
                        conn.getRemoteSocketAddress());

    }

    @Override
    public void onClose(
            WebSocket conn,
            int code,
            String reason,
            boolean remote) {

        System.out.println(
                "客户端断开");

    }

    @Override
    public void onMessage(
            WebSocket conn,
            String message) {

        System.out.println(
                "收到消息：" +
                        message);

    }

    @Override
    public void onError(
            WebSocket conn,
            Exception ex) {

        ex.printStackTrace();

    }

    @Override
    public void onStart() {

        System.out.println(
                "WebSocket服务器启动成功");

    }

}