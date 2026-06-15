-- =============================================================
-- BlackboardSystem - 用户管理模块 数据库初始化脚本
-- 在 SQL Server 中执行：在黑 BlackboardSystem 数据库中运行此脚本
-- 使用方法：
--   1. 打开 SSMS (SQL Server Management Studio)
--   2. 创建数据库：CREATE DATABASE BlackboardSystem
--   3. 选中 BlackboardSystem 数据库，执行本脚本
--   4. 或者通过 Navicat for SQL Server 连接后执行
-- =============================================================

-- 如果数据库不存在则创建
IF DB_ID('BlackboardSystem') IS NULL
BEGIN
    CREATE DATABASE BlackboardSystem;
END
GO

USE BlackboardSystem;
GO

-- ==================== 用户表 ====================
-- 存储用户基本信息：ID、昵称、偏好（JSON）、登录时间
IF OBJECT_ID('dbo.users', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.users (
        user_id     VARCHAR(20)     NOT NULL PRIMARY KEY,           -- 用户ID，如 U1, U2（自增生成）
        nickname    NVARCHAR(50)    NOT NULL,                       -- 用户昵称
        preferences NVARCHAR(MAX)   NULL,                           -- 偏好设置 JSON：{"key1":"val1","key2":"val2"}
        created_at  DATETIME        NOT NULL DEFAULT GETDATE(),     -- 首次登录时间
        updated_at  DATETIME        NOT NULL DEFAULT GETDATE(),     -- 最后活跃时间
    );
    PRINT '表 users 创建成功';
END
ELSE
BEGIN
    PRINT '表 users 已存在，跳过创建';
END
GO

-- ==================== 用户操作历史表 ====================
-- 记录用户的仿真运行历史
IF OBJECT_ID('dbo.user_history', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.user_history (
        id          INT IDENTITY(1,1) PRIMARY KEY,                  -- 自增主键
        user_id     VARCHAR(20)     NOT NULL,                       -- 用户ID（外键）
        action      NVARCHAR(50)    NOT NULL,                       -- 操作类型：START_SIM / PAUSE_SIM / RESET_SIM / LOGIN / UPDATE_PREF
        detail      NVARCHAR(MAX)   NULL,                           -- 操作详情 JSON
        created_at  DATETIME        NOT NULL DEFAULT GETDATE(),     -- 操作时间

        CONSTRAINT FK_user_history_user_id
            FOREIGN KEY (user_id) REFERENCES dbo.users(user_id)
            ON DELETE CASCADE
    );
    PRINT '表 user_history 创建成功';

    -- 索引：加速按用户+时间查询历史记录
    CREATE INDEX IX_user_history_user_time
        ON dbo.user_history(user_id, created_at DESC);
END
ELSE
BEGIN
    PRINT '表 user_history 已存在，跳过创建';
END
GO

-- ==================== 系统配置表 ====================
-- 存储当前活动用户等全局状态
IF OBJECT_ID('dbo.system_config', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.system_config (
        config_key   VARCHAR(50)   NOT NULL PRIMARY KEY,
        config_value NVARCHAR(MAX) NULL,
        updated_at   DATETIME      NOT NULL DEFAULT GETDATE()
    );
    -- 默认值：当前无登录用户
    INSERT INTO dbo.system_config(config_key, config_value)
    VALUES ('current_user_id', '');

    PRINT '表 system_config 创建成功';
END
ELSE
BEGIN
    PRINT '表 system_config 已存在，跳过创建';
END
GO

-- ==================== 完成 ====================
PRINT '========================================';
PRINT 'BlackboardSystem 用户管理表初始化完成！';
PRINT '  表: users           - 用户基本信息';
PRINT '  表: user_history    - 用户操作历史';
PRINT '  表: system_config   - 系统全局配置';
PRINT '========================================';
