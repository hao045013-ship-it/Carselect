package com.blackboard.display.user;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.blackboard.api.Blackboard;
import com.blackboard.display.db.DBConnection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * UserManagerAgent —— 用户管理知识源（人4）
 * <p>
 * 对接队友 ExplorationDB 已有表结构：
 * <ul>
 *   <li><b>users</b>: user_id, nickname, created_at, last_login, session_count, replay_count, prefs_json</li>
 *   <li><b>user_session</b>: id, user_id, session_id, joined_at</li>
 * </ul>
 * 当前用户状态用 Redis 存储（队友没有 system_config 表）
 * </p>
 */
public class UserManagerAgent {

    private static Blackboard board;
    private static boolean initialized = false;

    private UserManagerAgent() {}

    public static void init(Blackboard blackboard) {
        board = blackboard;
        initialized = true;
        System.out.println("[UserManager] 初始化完成（对接 ExplorationDB）");
    }

    // ==================== 命令入口 ====================

    public static String handleCommand(String commandJson) {
        try {
            JSONObject req = JSON.parseObject(commandJson);
            String cmd = req.getString("command");
            JSONObject data = req.getJSONObject("data");
            if (cmd == null) return error("缺少 command 字段");

            return switch (cmd.toUpperCase()) {
                case "LOGIN"           -> handleLogin(data);
                case "LOGOUT"          -> handleLogout();
                case "GET_PROFILE"     -> handleGetProfile();
                case "UPDATE_NICKNAME" -> handleUpdateNickname(data);
                case "SAVE_PREF"       -> handleSavePref(data);
                case "GET_PREFS"       -> handleGetPrefs();
                case "GET_HISTORY"        -> handleGetHistory(data);
                case "CHANGE_PASSWORD"   -> handleChangePassword(data);
                default                   -> error("未知命令: " + cmd);
            };
        } catch (Exception e) {
            System.err.println("[UserManager] 错误: " + e.getMessage());
            e.printStackTrace();
            return error("服务器内部错误: " + e.getMessage());
        }
    }

    // ==================== LOGIN ====================

    private static String handleLogin(JSONObject data) throws SQLException {
        String nickname = data != null ? data.getString("nickname") : null;
        String password = data != null ? data.getString("password") : null;
        if (nickname == null || nickname.trim().isEmpty()) return error("昵称不能为空");
        if (password == null || password.isEmpty()) return error("密码不能为空");
        nickname = nickname.trim();

        // 查找是否已有此昵称的用户
        String existSql = "SELECT user_id, prefs_json FROM dbo.users WHERE nickname = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(existSql)) {
            ps.setString(1, nickname);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    // === 已有用户：验证密码 ===
                    String userId = rs.getString("user_id");
                    String prefsJson = rs.getString("prefs_json");
                    String storedHash = getPasswordHash(prefsJson);

                    if (storedHash == null || storedHash.isEmpty()) {
                        return error("该账号未设置密码，请联系管理员");
                    }
                    if (!verifyPassword(password, storedHash)) {
                        return error("密码错误");
                    }

                    // 更新最后登录时间
                    updateLastLogin(userId);
                    syncRedisCurrentUser(userId, nickname);
                    return buildLoginResponse(userId, nickname);
                }
            }
        }

        // === 新用户注册 ===
        String userId = generateUserId();
        long now = System.currentTimeMillis();

        // 哈希密码存入 prefs_json
        String passwordHash = hashPassword(password);
        String prefsJson = "{\"__pwd\":\"" + passwordHash + "\"}";

        String insertSql = "INSERT INTO dbo.users (user_id, nickname, created_at, last_login, session_count, replay_count, prefs_json) VALUES (?, ?, ?, ?, 0, 0, ?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(insertSql)) {
            ps.setString(1, userId);
            ps.setString(2, nickname);
            ps.setLong(3, now);
            ps.setLong(4, now);
            ps.setString(5, prefsJson);
            ps.executeUpdate();
        }

        syncRedisCurrentUser(userId, nickname);
        return buildLoginResponse(userId, nickname);
    }

    // ==================== LOGOUT ====================

    private static String handleLogout() throws SQLException {
        String userId = getCurrentUserId();
        if (userId == null || userId.isEmpty()) return error("当前没有登录用户");

        // 更新 last_login
        String sql = "UPDATE dbo.users SET last_login = ? WHERE user_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, userId);
            ps.executeUpdate();
        }

        setCurrentUserId("");

        Map<String, Object> r = new HashMap<>();
        r.put("message", "已登出");
        r.put("userId", userId);
        return ok(r);
    }

    // ==================== GET_PROFILE ====================

    private static String handleGetProfile() throws SQLException {
        String userId = getCurrentUserId();
        if (userId == null || userId.isEmpty()) return error("当前没有登录用户");

        String sql = "SELECT user_id, nickname, prefs_json, created_at, last_login, session_count, replay_count FROM dbo.users WHERE user_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> p = new HashMap<>();
                    p.put("userId", rs.getString("user_id"));
                    p.put("nickname", rs.getString("nickname"));
                    String j = rs.getString("prefs_json");
                    p.put("preferences", j != null ? JSON.parseObject(j) : new HashMap<>());
                    p.put("createdAt", new java.util.Date(rs.getLong("created_at")).toString());
                    p.put("lastLogin", new java.util.Date(rs.getLong("last_login")).toString());
                    p.put("sessionCount", rs.getInt("session_count"));
                    p.put("replayCount", rs.getInt("replay_count"));
                    return ok(p);
                }
            }
        }
        return error("用户不存在: " + userId);
    }

    // ==================== UPDATE_NICKNAME ====================

    private static String handleUpdateNickname(JSONObject data) throws SQLException {
        String userId = getCurrentUserId();
        if (userId == null || userId.isEmpty()) return error("当前没有登录用户");

        String name = data != null ? data.getString("nickname") : null;
        if (name == null || name.trim().isEmpty()) return error("昵称不能为空");
        name = name.trim();

        String sql = "UPDATE dbo.users SET nickname = ?, last_login = ? WHERE user_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, userId);
            int n = ps.executeUpdate();
            if (n == 0) return error("用户不存在: " + userId);
        }

        syncRedisCurrentUser(userId, name);

        Map<String, Object> r = new HashMap<>();
        r.put("nickname", name);
        r.put("message", "昵称修改成功");
        return ok(r);
    }

    // ==================== SAVE_PREF / GET_PREFS ====================

    private static String handleSavePref(JSONObject data) throws SQLException {
        String userId = getCurrentUserId();
        if (userId == null || userId.isEmpty()) return error("当前没有登录用户");
        if (data == null) return error("缺少偏好数据");

        String key = data.getString("key");
        String value = data.getString("value");
        if (key == null || key.trim().isEmpty()) return error("偏好键名不能为空");
        if ("__pwd".equals(key)) return error("不能修改密码相关字段");

        // 读（含密码） → 改 → 写
        Map<String, Object> prefs = readPrefsJsonRaw(userId);
        prefs.put(key, value);
        String newJson = JSON.toJSONString(prefs);

        String sql = "UPDATE dbo.users SET prefs_json = ? WHERE user_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newJson);
            ps.setString(2, userId);
            ps.executeUpdate();
        }

        // 同步 Redis
        if (initialized && board != null) board.setUserPref(userId, key, value);

        Map<String, Object> r = new HashMap<>();
        r.put("key", key);
        r.put("value", value);
        r.put("message", "偏好保存成功");
        return ok(r);
    }

    private static String handleGetPrefs() throws SQLException {
        String userId = getCurrentUserId();
        if (userId == null || userId.isEmpty()) return error("当前没有登录用户");

        Map<String, Object> prefs = readPrefsJson(userId);
        Map<String, Object> r = new HashMap<>();
        r.put("preferences", prefs);
        return ok(r);
    }

    // ==================== GET_HISTORY ====================

    private static String handleGetHistory(JSONObject data) throws SQLException {
        String userId = getCurrentUserId();
        if (userId == null || userId.isEmpty()) return error("当前没有登录用户");

        int limit = data != null ? data.getIntValue("limit", 50) : 50;

        // 查询 user_session 表（用户参与的会话历史）
        String sql = "SELECT TOP (?) us.session_id, us.joined_at, s.map_width, s.map_height, s.car_count, s.start_time, s.end_time " +
                     "FROM dbo.user_session us LEFT JOIN dbo.session s ON us.session_id = s.session_id " +
                     "WHERE us.user_id = ? ORDER BY us.joined_at DESC";
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ps.setString(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> rec = new HashMap<>();
                    rec.put("sessionId", rs.getString("session_id"));
                    rec.put("joinedAt", new java.util.Date(rs.getLong("joined_at")).toString());
                    rec.put("mapSize", rs.getInt("map_width") + "x" + rs.getInt("map_height"));
                    rec.put("carCount", rs.getInt("car_count"));
                    long end = rs.getLong("end_time");
                    rec.put("status", end > 0 ? "已完成" : "运行中");
                    list.add(rec);
                }
            }
        }
        Map<String, Object> r = new HashMap<>();
        r.put("history", list);
        return ok(r);
    }

    // ==================== 辅助：当前用户（Redis） ====================

    private static String getCurrentUserId() {
        if (initialized && board != null) {
            String uid = board.getCurrentUser();
            if (uid != null && !uid.isEmpty()) return uid;
        }
        return null;
    }

    private static void setCurrentUserId(String userId) {
        if (initialized && board != null) {
            board.setCurrentUser(userId);
        }
    }

    private static void syncRedisCurrentUser(String userId, String nickname) {
        if (initialized && board != null) {
            board.setCurrentUser(userId);
            board.setUserPref(userId, "nickname", nickname);
        }
    }

    // ==================== 辅助：响应构建 ====================

    private static String buildLoginResponse(String userId, String nickname) throws SQLException {
        String sql = "SELECT created_at, last_login, session_count, replay_count, prefs_json FROM dbo.users WHERE user_id = ?";
        Map<String, Object> r = new HashMap<>();
        r.put("userId", userId);
        r.put("nickname", nickname);
        r.put("message", "登录成功");
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    r.put("createdAt", new java.util.Date(rs.getLong("created_at")).toString());
                    r.put("lastLogin", new java.util.Date(rs.getLong("last_login")).toString());
                    r.put("sessionCount", rs.getInt("session_count"));
                    r.put("replayCount", rs.getInt("replay_count"));
                    String pj = rs.getString("prefs_json");
                    Map<String, Object> prefsMap = pj != null ? JSON.parseObject(pj) : new HashMap<>();
                    prefsMap.remove("__pwd"); // 不暴露密码哈希给前端
                    r.put("preferences", prefsMap);
                }
            }
        }
        return ok(r);
    }

    // ==================== 辅助：数据库操作 ====================

    private static String generateUserId() throws SQLException {
        String sql = "SELECT ISNULL(MAX(CAST(SUBSTRING(user_id, 2, 10) AS INT)), 0) + 1 AS next_id FROM dbo.users WHERE user_id LIKE 'U%'";
        try (Connection conn = DBConnection.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) return "U" + rs.getInt("next_id");
        }
        return "U1";
    }

    private static Map<String, Object> readPrefsJson(String userId) throws SQLException {
        Map<String, Object> map = readPrefsJsonRaw(userId);
        map.remove("__pwd"); // 不暴露密码给前端
        return map;
    }

    /** 读取 prefs_json（含 __pwd），用于内部修改后写回 */
    private static Map<String, Object> readPrefsJsonRaw(String userId) throws SQLException {
        String sql = "SELECT prefs_json FROM dbo.users WHERE user_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String j = rs.getString("prefs_json");
                    if (j != null && !j.isEmpty()) return JSON.parseObject(j);
                }
            }
        }
        return new HashMap<>();
    }

    // ==================== CHANGE_PASSWORD ====================

    private static String handleChangePassword(JSONObject data) throws SQLException {
        String userId = getCurrentUserId();
        if (userId == null || userId.isEmpty()) return error("请先登录");

        String oldPwd = data != null ? data.getString("oldPassword") : null;
        String newPwd = data != null ? data.getString("newPassword") : null;
        if (oldPwd == null || oldPwd.isEmpty()) return error("请输入当前密码");
        if (newPwd == null || newPwd.length() < 6) return error("新密码至少6位");

        // 验证旧密码
        String sql = "SELECT prefs_json FROM dbo.users WHERE user_id = ?";
        try (Connection conn = DBConnection.getConnection();
            PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String storedHash = getPasswordHash(rs.getString("prefs_json"));
                    if (storedHash == null) return error("该账号未设置密码");
                    if (!verifyPassword(oldPwd, storedHash)) return error("当前密码错误");
                } else {
                    return error("用户不存在");
                }
            }
        }

        // 更新密码（保留其他偏好）
        Map<String, Object> prefs = readPrefsJsonRaw(userId);
        prefs.put("__pwd", hashPassword(newPwd));
        String newJson = JSON.toJSONString(prefs);

        String updateSql = "UPDATE dbo.users SET prefs_json = ? WHERE user_id = ?";
        try (Connection conn = DBConnection.getConnection();
            PreparedStatement ps = conn.prepareStatement(updateSql)) {
            ps.setString(1, newJson);
            ps.setString(2, userId);
            ps.executeUpdate();
        }

        Map<String, Object> r = new HashMap<>();
        r.put("message", "密码修改成功");
        return ok(r);
    }

    // ==================== 密码哈希 ====================

    private static final String SALT_PREFIX = "bb$"; // 简单盐前缀

    private static String hashPassword(String password) {
        try {
            String salted = SALT_PREFIX + password;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(salted.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            // 降级：简单哈希
            return SALT_PREFIX + Integer.toHexString(password.hashCode());
        }
    }

    private static boolean verifyPassword(String password, String storedHash) {
        String newHash = hashPassword(password);
        return newHash.equals(storedHash);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** 从 prefs_json 中提取密码哈希 */
    private static String getPasswordHash(String prefsJson) {
        if (prefsJson == null || prefsJson.isEmpty()) return null;
        try {
            JSONObject prefs = JSON.parseObject(prefsJson);
            return prefs.getString("__pwd");
        } catch (Exception e) {
            return null;
        }
    }

    /** 更新最后登录时间 */
    private static void updateLastLogin(String userId) throws SQLException {
        String sql = "UPDATE dbo.users SET last_login = ? WHERE user_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, userId);
            ps.executeUpdate();
        }
    }

    // ==================== JSON 响应 ====================

    private static String ok(Object data) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("data", data);
        resp.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return JSON.toJSONString(resp);
    }

    private static String error(String msg) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", false);
        resp.put("error", msg);
        resp.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return JSON.toJSONString(resp);
    }
}
