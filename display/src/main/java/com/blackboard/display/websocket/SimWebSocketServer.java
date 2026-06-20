package com.blackboard.display.websocket;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.blackboard.display.user.UserManagerAgent;
import com.blackboard.util.SimpleBridge;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * WebSocket server for display.
 * Frontend simulation commands are forwarded to backend knowledge sources through MQ.
 */
public class SimWebSocketServer extends WebSocketServer {

    private static final Set<String> USER_COMMANDS = Set.of(
            "LOGIN", "REGISTER", "LOGOUT", "GET_PROFILE", "UPDATE_NICKNAME",
            "SAVE_PREF", "GET_PREFS", "ADD_HISTORY", "GET_HISTORY",
            "CHANGE_PASSWORD"
    );

    private static final Set<String> BACKEND_COMMANDS = Set.of(
            "SET_CONFIG", "START", "PAUSE", "RESUME", "RESET", "SET_SPEED",
            "ADD_CAR", "ADD_CARS_BATCH", "REMOVE_CAR",
            "SET_OBSTACLE", "RANDOM_OBSTACLE", "CLEAR_OBSTACLE"
    );

    public SimWebSocketServer(int port) {
        super(new InetSocketAddress(port));
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("[WebSocket] client connected: " + conn.getRemoteSocketAddress());
        try {
            conn.send(SimpleBridge.readFullState());
        } catch (Exception e) {
            System.err.println("[WebSocket] failed to push initial state: " + e.getMessage());
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        System.out.println("[WebSocket] client disconnected");
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        System.out.println("[WebSocket] received: " + (message.length() > 80 ? message.substring(0, 80) + "..." : message));

        JSONObject req;
        try {
            req = JSON.parseObject(message);
        } catch (Exception e) {
            conn.send(error("Invalid JSON"));
            return;
        }

        String cmd = req.getString("command");
        if (cmd == null || cmd.isBlank()) {
            cmd = req.getString("cmd");
        }
        JSONObject data = req.getJSONObject("data");
        if (cmd == null || cmd.isBlank()) {
            conn.send(error("Missing command"));
            return;
        }

        String normalized = cmd.toUpperCase();

        if (USER_COMMANDS.contains(normalized)) {
            conn.send(UserManagerAgent.handleCommand(message));
            return;
        }

        if (BACKEND_COMMANDS.contains(normalized)) {
            try {
                forwardBackendCommand(normalized, data);
                conn.send(ok("Command forwarded: " + normalized));
                System.out.println("[WebSocket] forwarded to backend: " + normalized);
            } catch (Exception e) {
                conn.send(error("Command forwarding failed: " + e.getMessage()));
                System.err.println("[WebSocket] command forwarding failed: " + e.getMessage());
            }
            return;
        }

        conn.send(error("Unknown command: " + cmd));
        System.out.println("[WebSocket] unknown command: " + cmd);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("[WebSocket] error: " + ex.getMessage());
    }

    @Override
    public void onStart() {
        System.out.println("[WebSocket] server started on port: " + getPort());
    }

    private void forwardBackendCommand(String cmd, JSONObject data) {
        switch (cmd) {
            case "SET_CONFIG" -> SimpleBridge.setConfig(toConfigMap(data));
            case "START" -> {
                if (data != null && !data.isEmpty()) {
                    SimpleBridge.setConfig(toConfigMap(data));
                }
                SimpleBridge.sendCommand("START");
            }
            case "ADD_CAR" -> {
                if (data == null) {
                    throw new IllegalArgumentException("ADD_CAR missing data");
                }
                String carId = data.getString("carId");
                int row = data.containsKey("row") ? data.getIntValue("row") : data.getIntValue("y");
                int col = data.containsKey("col") ? data.getIntValue("col") : data.getIntValue("x");
                SimpleBridge.addCar(carId, row, col);
            }
            case "ADD_CARS_BATCH" -> SimpleBridge.sendCommand("ADD_CARS_BATCH", toMap(data));
            case "SET_OBSTACLE" -> {
                if (data == null) {
                    throw new IllegalArgumentException("SET_OBSTACLE missing data");
                }
                int row = data.getIntValue("row");
                int col = data.getIntValue("col");
                boolean value = !data.containsKey("value") || data.getBooleanValue("value");
                SimpleBridge.setObstacle(row, col, value);
            }
            case "RANDOM_OBSTACLE" -> SimpleBridge.randomObstacles(data == null ? 5 : data.getIntValue("density", 5));
            case "CLEAR_OBSTACLE" -> SimpleBridge.clearAllObstacles();
            default -> SimpleBridge.sendCommand(cmd, toMap(data));
        }
    }

    private Map<String, Object> toConfigMap(JSONObject data) {
        Map<String, Object> map = toMap(data);
        if (map.containsKey("robotCount") && !map.containsKey("carCount")) {
            map.put("carCount", map.get("robotCount"));
        }
        if (map.containsKey("density") && !map.containsKey("obstacleDensity")) {
            map.put("obstacleDensity", map.get("density"));
        }
        map.putIfAbsent("algorithm", "A_STAR");
        return map;
    }

    private Map<String, Object> toMap(JSONObject data) {
        Map<String, Object> map = new HashMap<>();
        if (data == null) {
            return map;
        }
        for (String key : data.keySet()) {
            map.put(key, data.get(key));
        }
        return map;
    }

    private String ok(String message) {
        JSONObject resp = new JSONObject();
        resp.put("success", true);
        resp.put("message", message);
        return resp.toJSONString();
    }

    private String error(String message) {
        JSONObject resp = new JSONObject();
        resp.put("success", false);
        resp.put("error", message);
        return resp.toJSONString();
    }

    public void broadcast(String message) {
        for (WebSocket conn : getConnections()) {
            conn.send(message);
        }
    }
}
