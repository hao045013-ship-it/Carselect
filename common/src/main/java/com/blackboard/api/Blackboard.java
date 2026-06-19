package com.blackboard.api;

import java.util.List;
import java.util.Map;

/**
 * 黑板接口 —— 所有 Redis 操作的抽象
 * 实现类由组长（人1）编写，其他人依赖此接口开发
 */
public interface Blackboard {
    // ==================== 地图尺寸 ====================
    int getMapWidth();
    int getMapHeight();
    // ==================== 节拍 ====================
    long getCurrentTick();
    void setCurrentTick(long tick);

    // ==================== 静态障碍物 ====================
    void setStaticBlock(int row, int col, boolean value);
    boolean hasStaticBlock(int row, int col);
    boolean[] getFullStaticBlock();
    void randomStaticBlocks(double density);
    void clearStaticBlocks();

    // ==================== 动态障碍物，小车占位 ====================
    void setDynamicBlock(int row, int col, boolean value);
    boolean hasDynamicBlock(int row, int col);
    boolean[] getFullDynamicBlock();
    void clearDynamicBlocks();

    // ==================== 综合障碍判断 ====================
    boolean hasBlock(int row, int col);

    // ==================== 车辆列表 ====================
    void addCar(String carId);
    void removeCar(String carId);
    List<String> getCarList();
    boolean carExists(String carId);
    boolean tryAddCar(String carId, int row, int col, String status);


    // ==================== 轨迹回放 ====================
    void appendTrace(String carId, long tick, int x, int y);
    List<String> getTrace(String carId);

    // ==================== 统计 ====================
    void incrementBlockedCount(String carId);
    int getBlockedCount(String carId);
    void incrementRoutePlanCount(String carId);
    int getRoutePlanCount(String carId);
    void saveCoverageHistory(long tick, double coverage);
    List<String> getCoverageHistory();

    // ==================== 地图视野 ====================
    void exploreCell(int row, int col);
    void revealVision(int centerX, int centerY, int radius);
    boolean isExplored(int row, int col);
    boolean[] getFullMapView();

    // ==================== 障碍物 ====================
    void setObstacle(int row, int col, boolean value);
    boolean hasObstacle(int row, int col);
    boolean[] getFullMapBlock();
    void randomObstacles(double density);
    void clearAllObstacles();

    // ==================== 汽车位置 ====================
    void setPosition(String carId, int row, int col);
    Map<String, String> getPosition(String carId);

    // ==================== 目标 ====================
    void setTarget(String carId, int x, int y);
    Map<String, String> getTarget(String carId);
    void clearTarget(String carId);

    // ==================== 路径 ====================
    void pushRoute(String carId, String json);
    String popRoute(String carId);
    String peekRoute(String carId);
    void clearRoute(String carId);
    long getRouteLength(String carId);
    List<String> getRouteList(String carId);
    // ==================== 状态 ====================
    void setStatus(String carId, String status);
    String getStatus(String carId);

    // ==================== 步数 ====================
    int getSteps(String carId);
    void incrementSteps(String carId);

    // ==================== 受阻 ====================
    void setBlockedTick(String carId, long tick);
    long getBlockedTick(String carId);

    // ==================== 配置 ====================
    void setTaskConfig(Map<String, String> config);
    Map<String, String> getTaskConfig();

    // ==================== 快照与统计 ====================
    void saveSnapshot(String json);
    List<String> getAllSnapshots();
    int getSnapshotCount();

    void setStatsReport(String json);
    String getStatsReport();

    // ==================== 注册 ====================
    void registerCar(String carId);
    List<String> getOnlineCars();
    void unregisterCar(String carId);
    long getCarCount();
    void registerCarInfo(String carId, int row, int col, String status);
    void heartbeatCar(String carId, String status);
    void registerKnowledgeSource(String agentId, String type, String status);
    void heartbeatKnowledgeSource(String agentId, String status);

    // ==================== 日志 ====================
    void addLogEntry(String entry);
    List<String> getLogs(int count);

    // ==================== 用户管理 ====================
    String createUser(String nickname);
    void setCurrentUser(String userId);
    String getCurrentUser();
    String getUserNickname(String userId);
    void setUserPref(String userId, String key, String value);
    Map<String, String> getUserPrefs(String userId);
    void addUserHistory(String userId, String record);
    List<String> getUserHistory(String userId);

    // ==================== 原子操作 ====================
    boolean atomicMove(String carId, int oldX, int oldY, int newX, int newY, int visionRadius);
    boolean acquireControllerLeadership(String instanceId, int ttlSeconds);
    boolean refreshControllerLeadership(String instanceId, int ttlSeconds);
    boolean isControllerLeader(String instanceId);
    void releaseControllerLeadership(String instanceId);
    void updateControllerHeartbeat(String instanceId, String status);

    // ==================== 工具 ====================
    double getExploredPercent();
    void clearAll();
    void close();
}
