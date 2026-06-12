package com.blackboard.display.websocket;

public class WebSocketBridge {

    private static SimWebSocketServer server;

    public static void init() {

        server = new SimWebSocketServer(8887);

        server.start();

    }


    public static void broadcast(
            String msg) {

        server.broadcast(msg);

    }

}