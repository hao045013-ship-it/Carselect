package com.blackboard.display.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * SQL Server 数据库连接工具类
 * <p>
 * 管理 UserManager 模块的数据库连接。
 * 队友共享数据库时，统一使用此连接配置。
 * </p>
 */
public class DBConnection {

    private static String url;
    private static String username;
    private static String password;
    private static Connection connection;

    private DBConnection() {}

    /**
     * 初始化数据库连接参数
     *
     * @param url      JDBC URL，例如 jdbc:sqlserver://localhost:1433;databaseName=BlackboardSystem
     * @param username 数据库用户名
     * @param password 数据库密码
     */
    public static void init(String url, String username, String password) {
        DBConnection.url = url;
        DBConnection.username = username;
        DBConnection.password = password;
    }

    /**
     * 获取数据库连接
     * <p>
     * 如果已有连接且未关闭，则复用；否则创建新连接。
     * </p>
     */
    public static Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            try {
                Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
            } catch (ClassNotFoundException e) {
                throw new SQLException("SQL Server JDBC 驱动未找到", e);
            }
            connection = DriverManager.getConnection(url, username, password);
        }
        return connection;
    }

    /**
     * 关闭数据库连接
     */
    public static void close() {
        if (connection != null) {
            try {
                if (!connection.isClosed()) {
                    connection.close();
                }
            } catch (SQLException e) {
                System.err.println("[DBConnection] 关闭连接失败: " + e.getMessage());
            }
            connection = null;
        }
    }
}
