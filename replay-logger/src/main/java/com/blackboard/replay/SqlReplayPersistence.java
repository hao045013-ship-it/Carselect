package com.blackboard.replay;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL Server 持久化 —— 封装 session / snapshot / event_log 表的原生 JDBC 操作
 */
public class SqlReplayPersistence {

    private Connection connection;

    /**
     * @param url      JDBC 连接 URL
     * @param username 数据库用户名
     * @param password 数据库密码
     */
    public SqlReplayPersistence(String url, String username, String password) {
        try {
            connection = DriverManager.getConnection(url, username, password);
            ensureIndexes();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void ensureIndexes() {
        if (connection == null) {
            return;
        }
        String sql = "IF OBJECT_ID('dbo.snapshot', 'U') IS NOT NULL "
                + "AND NOT EXISTS ("
                + "SELECT 1 FROM sys.indexes "
                + "WHERE name = 'idx_snapshot_session_tick' "
                + "AND object_id = OBJECT_ID('dbo.snapshot')) "
                + "BEGIN "
                + "CREATE INDEX idx_snapshot_session_tick ON dbo.snapshot(session_id, tick) "
                + "END";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.execute();
        } catch (SQLException e) {
            System.err.println("[SqlReplayPersistence] 创建 snapshot 索引失败: " + e.getMessage());
        }
    }

    /**
     * 创建新的回放会话记录
     */
    public void startSession(String sessionId, long startTime, int mapWidth, int mapHeight, int carCount) {
        String sql = "INSERT INTO session(session_id, start_time, map_width, map_height, car_count) "
                + "VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setLong(2, startTime);
            ps.setInt(3, mapWidth);
            ps.setInt(4, mapHeight);
            ps.setInt(5, carCount);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * 结束回放会话，写入结束时间
     */
    public void endSession(String sessionId, long endTime) {
        if (connection == null) {
            return;
        }
        String sql = "UPDATE session SET end_time = ? WHERE session_id = ? AND end_time IS NULL";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, endTime);
            ps.setString(2, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * 插入一条快照记录
     */
    public void insertSnapshot(String sessionId, long ts, long tick, double coverage, String stateJson) {
        if (connection == null) {
            return;
        }
        String sql = "IF NOT EXISTS (SELECT 1 FROM snapshot WHERE session_id = ? AND tick = ?) "
                + "BEGIN "
                + "INSERT INTO snapshot(session_id, ts, tick, coverage, state_json) "
                + "VALUES (?, ?, ?, ?, ?) "
                + "END";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setLong(2, tick);
            ps.setString(3, sessionId);
            ps.setLong(4, ts);
            ps.setLong(5, tick);
            ps.setDouble(6, coverage);
            ps.setString(7, stateJson);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * 插入一条事件日志（MOVE 等）
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

    /**
     * 分页查询全部会话
     *
     * @return Map 含 total 和 data 两个键
     */
    public Map<String, Object> listSessions(int page, int size) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", 0);
        result.put("data", List.of());

        if (connection == null) {
            return result;
        }

        int offset = Math.max(page, 0) * Math.max(size, 1);
        int limit = Math.max(size, 1);

        try {
            long total = 0;
            String countSql = """
                    SELECT COUNT(*)
                    FROM session s
                    WHERE EXISTS (
                        SELECT 1 FROM snapshot sn WHERE sn.session_id = s.session_id
                    )
                    """;
            try (PreparedStatement ps = connection.prepareStatement(countSql);
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    total = rs.getLong(1);
                }
            }

            List<Map<String, Object>> sessions = new ArrayList<>();
            String sql = """
                    SELECT s.session_id, s.start_time, s.end_time, s.car_count, s.note
                    FROM session s
                    WHERE EXISTS (
                        SELECT 1 FROM snapshot sn WHERE sn.session_id = s.session_id
                    )
                    ORDER BY s.start_time DESC
                    OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, offset);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("sessionId", rs.getString("session_id"));
                        row.put("startTime", rs.getLong("start_time"));
                        long endTime = rs.getLong("end_time");
                        row.put("endTime", rs.wasNull() ? null : endTime);
                        row.put("carCount", rs.getInt("car_count"));
                        row.put("note", rs.getString("note"));
                        sessions.add(row);
                    }
                }
            }

            result.put("total", total);
            result.put("data", sessions);
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return result;
    }

    /**
     * 查询指定会话的快照列表，支持 tick 范围过滤
     */
    public Map<String, Object> listSnapshots(String sessionId, Long fromTick, Long toTick) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", 0);
        result.put("snapshots", List.of());

        if (connection == null) {
            return result;
        }

        StringBuilder where = new StringBuilder("WHERE session_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(sessionId);

        if (fromTick != null) {
            where.append(" AND tick >= ?");
            params.add(fromTick);
        }
        if (toTick != null) {
            where.append(" AND tick <= ?");
            params.add(toTick);
        }
        String sql = "WITH ranked AS ("
                + "SELECT id, ts, tick, coverage, state_json, "
                + "ROW_NUMBER() OVER (PARTITION BY tick ORDER BY id ASC) AS rn "
                + "FROM snapshot "
                + where
                + ") "
                + "SELECT id, ts, tick, coverage, state_json FROM ranked "
                + "WHERE rn = 1 ORDER BY tick ASC";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                Object param = params.get(i);
                if (param instanceof String) {
                    ps.setString(i + 1, (String) param);
                } else if (param instanceof Long) {
                    ps.setLong(i + 1, (Long) param);
                }
            }

            List<Map<String, Object>> snapshots = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("ts", rs.getLong("ts"));
                    row.put("tick", rs.getLong("tick"));
                    row.put("coverage", rs.getDouble("coverage"));
                    row.put("stateJson", rs.getString("state_json"));
                    snapshots.add(row);
                }
            }

            result.put("total", snapshots.size());
            result.put("snapshots", snapshots);
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return result;
    }

    /**
     * 查找 tick <= targetTick 的最近一条快照
     */
    public Map<String, Object> findNearestSnapshot(String sessionId, long targetTick) {
        if (connection == null) {
            return null;
        }
        String sql = "SELECT TOP 1 id, ts, tick, coverage, state_json FROM snapshot "
                + "WHERE session_id = ? AND tick <= ? ORDER BY tick DESC";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setLong(2, targetTick);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("ts", rs.getLong("ts"));
                    row.put("tick", rs.getLong("tick"));
                    row.put("coverage", rs.getDouble("coverage"));
                    row.put("stateJson", rs.getString("state_json"));
                    return row;
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 查询 tick 在 (afterTick, upToTick] 范围内的 MOVE 事件，按 ts 升序
     */
    public List<Map<String, Object>> listMoveEvents(String sessionId, long afterTick, long upToTick) {
        List<Map<String, Object>> events = new ArrayList<>();
        if (connection == null) {
            return events;
        }

        String sql = "SELECT ts, car_id, x, y, extra_json FROM event_log "
                + "WHERE session_id = ? AND event_type = 'MOVE' "
                + "AND CAST(JSON_VALUE(extra_json, '$.tick') AS BIGINT) > ? "
                + "AND CAST(JSON_VALUE(extra_json, '$.tick') AS BIGINT) <= ? "
                + "ORDER BY ts ASC";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setLong(2, afterTick);
            ps.setLong(3, upToTick);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("ts", rs.getLong("ts"));
                    row.put("carId", rs.getString("car_id"));
                    row.put("x", rs.getInt("x"));
                    row.put("y", rs.getInt("y"));
                    row.put("extraJson", rs.getString("extra_json"));
                    events.add(row);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return events;
    }

    /**
     * 查询指定会话、指定车辆的全部 MOVE 事件，按 ts 升序
     */
    public List<Map<String, Object>> listTraceEvents(String sessionId, String carId) {
        List<Map<String, Object>> events = new ArrayList<>();
        if (connection == null) {
            return events;
        }

        String sql = "SELECT ts, x, y, extra_json FROM event_log "
                + "WHERE session_id = ? AND car_id = ? AND event_type = 'MOVE' "
                + "ORDER BY ts ASC";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setString(2, carId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("ts", rs.getLong("ts"));
                    row.put("x", rs.getInt("x"));
                    row.put("y", rs.getInt("y"));
                    row.put("extraJson", rs.getString("extra_json"));
                    events.add(row);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return events;
    }
}
