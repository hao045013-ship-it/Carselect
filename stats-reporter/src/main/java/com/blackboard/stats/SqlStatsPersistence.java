package com.blackboard.stats;

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
 * SQL Server 持久化 —— 封装 session_stats / coverage_curve 表的原生 JDBC 操作
 */
public class SqlStatsPersistence {

    private Connection connection;

    public SqlStatsPersistence(String url, String username, String password) {
        try {
            connection = DriverManager.getConnection(url, username, password);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * 查询最新一条会话 ID（start_time 最大）
     */
    public String getLatestSessionId() {
        if (connection == null) {
            return null;
        }
        String sql = "SELECT TOP 1 session_id FROM session ORDER BY start_time DESC";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getString("session_id");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 插入覆盖率曲线采样点
     */
    public void insertCoverageCurve(String sessionId, long ts, long tick,
                                    double coverage, int exploredCells) {
        if (connection == null) {
            return;
        }
        String sql = "INSERT INTO coverage_curve(session_id, ts, tick, coverage, explored_cells) "
                + "VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setLong(2, ts);
            ps.setLong(3, tick);
            ps.setDouble(4, coverage);
            ps.setInt(5, exploredCells);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * 插入或更新会话统计（duration_ms 不在此写入）
     */
    public void upsertSessionStats(String sessionId, int totalCells, int exploredCells,
                                   double coverageRate, int totalMoves, int totalBlocked,
                                   int totalNavCount, double avgNavEfficiency,
                                   String perCarStatsJson, long updatedAt) {
        if (connection == null) {
            return;
        }
        try {
            boolean exists = false;
            String countSql = "SELECT COUNT(*) FROM session_stats WHERE session_id = ?";
            try (PreparedStatement ps = connection.prepareStatement(countSql)) {
                ps.setString(1, sessionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        exists = rs.getInt(1) > 0;
                    }
                }
            }

            if (exists) {
                String updateSql = "UPDATE session_stats SET total_cells=?, explored_cells=?, "
                        + "coverage_rate=?, total_moves=?, total_blocked=?, total_nav_count=?, "
                        + "avg_nav_efficiency=?, per_car_stats_json=?, updated_at=? WHERE session_id=?";
                try (PreparedStatement ps = connection.prepareStatement(updateSql)) {
                    ps.setInt(1, totalCells);
                    ps.setInt(2, exploredCells);
                    ps.setDouble(3, coverageRate);
                    ps.setInt(4, totalMoves);
                    ps.setInt(5, totalBlocked);
                    ps.setInt(6, totalNavCount);
                    ps.setDouble(7, avgNavEfficiency);
                    ps.setString(8, perCarStatsJson);
                    ps.setLong(9, updatedAt);
                    ps.setString(10, sessionId);
                    ps.executeUpdate();
                }
            } else {
                String insertSql = "INSERT INTO session_stats(session_id, total_cells, explored_cells, "
                        + "coverage_rate, total_moves, total_blocked, total_nav_count, "
                        + "avg_nav_efficiency, per_car_stats_json, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = connection.prepareStatement(insertSql)) {
                    ps.setString(1, sessionId);
                    ps.setInt(2, totalCells);
                    ps.setInt(3, exploredCells);
                    ps.setDouble(4, coverageRate);
                    ps.setInt(5, totalMoves);
                    ps.setInt(6, totalBlocked);
                    ps.setInt(7, totalNavCount);
                    ps.setDouble(8, avgNavEfficiency);
                    ps.setString(9, perCarStatsJson);
                    ps.setLong(10, updatedAt);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * 会话结束时写入总时长
     */
    public void finalizeSessionStats(String sessionId, long durationMs) {
        if (connection == null) {
            return;
        }
        String sql = "UPDATE session_stats SET duration_ms = ? WHERE session_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, durationMs);
            ps.setString(2, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * 查询指定会话的统计概览
     */
    public Map<String, Object> getSessionStats(String sessionId) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (connection == null) {
            return result;
        }
        String sql = "SELECT * FROM session_stats WHERE session_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    result.put("sessionId", rs.getString("session_id"));
                    result.put("totalCells", rs.getInt("total_cells"));
                    result.put("exploredCells", rs.getInt("explored_cells"));
                    result.put("coverageRate", rs.getDouble("coverage_rate"));
                    result.put("totalMoves", rs.getInt("total_moves"));
                    result.put("totalBlocked", rs.getInt("total_blocked"));
                    result.put("totalNavCount", rs.getInt("total_nav_count"));
                    double avgEff = rs.getDouble("avg_nav_efficiency");
                    result.put("avgNavEfficiency", rs.wasNull() ? null : avgEff);
                    long duration = rs.getLong("duration_ms");
                    result.put("durationMs", rs.wasNull() ? null : duration);
                    result.put("perCarStatsJson", rs.getString("per_car_stats_json"));
                    result.put("updatedAt", rs.getLong("updated_at"));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return result;
    }

    /**
     * 查询指定会话的覆盖率曲线（按 tick 升序）
     */
    public Map<String, Object> getCoverageCurve(String sessionId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        List<Map<String, Object>> points = new ArrayList<>();

        if (connection == null) {
            result.put("points", points);
            return result;
        }

        String sql = "SELECT tick, coverage, explored_cells FROM coverage_curve "
                + "WHERE session_id = ? ORDER BY tick ASC";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> point = new LinkedHashMap<>();
                    point.put("tick", rs.getLong("tick"));
                    point.put("coverage", rs.getDouble("coverage"));
                    point.put("exploredCells", rs.getInt("explored_cells"));
                    points.add(point);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        result.put("points", points);
        return result;
    }

    /**
     * 查询最近 N 条覆盖率曲线采样点（按 tick 升序）
     */
    public List<long[]> getRecentCoveragePoints(String sessionId, int limit) {
        List<long[]> points = new ArrayList<>();
        if (connection == null) {
            return points;
        }

        String sql = "SELECT tick, explored_cells FROM ("
                + "SELECT TOP (?) tick, explored_cells FROM coverage_curve "
                + "WHERE session_id = ? ORDER BY tick DESC"
                + ") sub ORDER BY tick ASC";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ps.setString(2, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    points.add(new long[]{rs.getLong("tick"), rs.getInt("explored_cells")});
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return points;
    }

    /**
     * 从 event_log 聚合各车贡献数据
     */
    public List<Map<String, Object>> getCarContribution(String sessionId) {
        List<Map<String, Object>> cars = new ArrayList<>();
        if (connection == null) {
            return cars;
        }

        String sql = "SELECT car_id, "
                + "COUNT(CASE WHEN event_type='MOVE' THEN 1 END) AS moves, "
                + "COUNT(CASE WHEN event_type='BLOCKED' THEN 1 END) AS blocked, "
                + "COUNT(CASE WHEN event_type='ROUTE_PLANNED' THEN 1 END) AS nav_count "
                + "FROM event_log WHERE session_id = ? GROUP BY car_id";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("carId", rs.getString("car_id"));
                    row.put("moves", rs.getInt("moves"));
                    row.put("blocked", rs.getInt("blocked"));
                    row.put("navCount", rs.getInt("nav_count"));
                    cars.add(row);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return cars;
    }

    /**
     * 分页查询会话列表（关联 session_stats）
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
            String countSql = "SELECT COUNT(*) FROM session";
            try (PreparedStatement ps = connection.prepareStatement(countSql);
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    total = rs.getLong(1);
                }
            }

            List<Map<String, Object>> sessions = new ArrayList<>();
            String sql = "SELECT s.session_id, s.start_time, s.end_time, "
                    + "ss.coverage_rate, ss.duration_ms "
                    + "FROM session s LEFT JOIN session_stats ss ON s.session_id = ss.session_id "
                    + "ORDER BY s.start_time DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
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
                        double coverage = rs.getDouble("coverage_rate");
                        row.put("coverageRate", rs.wasNull() ? null : coverage);
                        long duration = rs.getLong("duration_ms");
                        row.put("durationMs", rs.wasNull() ? null : duration);
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
     * 从 session 表读取地图尺寸，用于预测接口兜底
     */
    public int getSessionTotalCells(String sessionId) {
        if (connection == null) {
            return 0;
        }
        String sql = "SELECT map_width, map_height FROM session WHERE session_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("map_width") * rs.getInt("map_height");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }
}
