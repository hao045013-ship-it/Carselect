package com.blackboard.exploration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * SQL Server 持久化 —— 封装 session / event_log 表的原生 JDBC 操作
 */
public class SqlPersistence {

    private Connection connection;

    /**
     * @param url      JDBC 连接 URL
     * @param username 数据库用户名
     * @param password 数据库密码
     */
    public SqlPersistence(String url, String username, String password) {
        try {
            connection = DriverManager.getConnection(url, username, password);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * 创建新的探索会话记录
     */
    public void startSession(String sessionId, long startTime, int carCount) {
        if (connection == null) {
            return;
        }
        String sql = "INSERT INTO session(session_id, start_time, map_width, map_height, car_count) "
                + "VALUES (?, ?, 30, 30, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setLong(2, startTime);
            ps.setInt(3, carCount);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * 结束探索会话，写入结束时间
     */
    public void endSession(String sessionId, long endTime) {
        if (connection == null) {
            return;
        }
        String sql = "UPDATE session SET end_time = ? WHERE session_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, endTime);
            ps.setString(2, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * 插入一条事件日志
     */
    public void insertEventLog(String sessionId, long ts, String eventType,
                               String carId, Integer x, Integer y, String extraJson) {
        if (connection == null) {
            return;
        }
        String sql = "INSERT INTO event_log(session_id, ts, event_type, car_id, x, y, extra_json) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setLong(2, ts);
            ps.setString(3, eventType);
            ps.setString(4, carId);
            if (x != null) {
                ps.setInt(5, x);
            } else {
                ps.setNull(5, java.sql.Types.INTEGER);
            }
            if (y != null) {
                ps.setInt(6, y);
            } else {
                ps.setNull(6, java.sql.Types.INTEGER);
            }
            ps.setString(7, extraJson);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
