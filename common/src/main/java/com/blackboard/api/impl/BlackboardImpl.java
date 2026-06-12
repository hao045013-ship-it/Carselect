package com.blackboard.api.impl;

import com.blackboard.api.Blackboard;
import com.blackboard.constant.RedisKeys;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Transaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 黑板实现类 —— 封装所有 Redis 操作
 *
 * 人1开发，其他人通过 Blackboard 接口使用
 */
public class BlackboardImpl implements Blackboard {

    private final JedisPool pool;
    private final int mapWidth;
    private final int mapHeight;

    public BlackboardImpl(String host, int port) {
        this.pool = new JedisPool(host, port);
        this.mapWidth = RedisKeys.MAP_WIDTH;
        this.mapHeight = RedisKeys.MAP_HEIGHT;
    }

    // ==================== 获取连接 ====================
    private Jedis getJedis() {
        return pool.getResource();
    }

    // ==================== 地图视野 ====================
    @Override
    public void exploreCell(int row, int col) {
        try (Jedis jedis = getJedis()) {
            jedis.setbit(RedisKeys.MAP_VIEW, RedisKeys.index(row, col), true);
        }
    }

    @Override
    public boolean isExplored(int row, int col) {
        try (Jedis jedis = getJedis()) {
            return jedis.getbit(RedisKeys.MAP_VIEW, RedisKeys.index(row, col));
        }
    }

    @Override
    public boolean[] getFullMapView() {
        try (Jedis jedis = getJedis()) {
            boolean[] result = new boolean[mapWidth * mapHeight];
            for (int i = 0; i < result.length; i++) {
                result[i] = jedis.getbit(RedisKeys.MAP_VIEW, i);
            }
            return result;
        }
    }

    // ==================== 障碍物 ====================
    @Override
    public void setObstacle(int row, int col, boolean value) {
        try (Jedis jedis = getJedis()) {
            jedis.setbit(RedisKeys.MAP_BLOCK, RedisKeys.index(row, col), value);
        }
    }

    @Override
    public boolean hasObstacle(int row, int col) {
        try (Jedis jedis = getJedis()) {
            return jedis.getbit(RedisKeys.MAP_BLOCK, RedisKeys.index(row, col));
        }
    }

    @Override
    public boolean[] getFullMapBlock() {
        try (Jedis jedis = getJedis()) {
            boolean[] result = new boolean[mapWidth * mapHeight];
            for (int i = 0; i < result.length; i++) {
                result[i] = jedis.getbit(RedisKeys.MAP_BLOCK, i);
            }
            return result;
        }
    }

    @Override
    public void randomObstacles(double density) {
        try (Jedis jedis = getJedis()) {
            // 先清空
            for (int i = 0; i < mapWidth * mapHeight; i++) {
                jedis.setbit(RedisKeys.MAP_BLOCK, i, false);
            }
            // 随机生成（避开边界，留给小车初始位置）
            for (int r = 1; r < mapHeight - 1; r++) {
                for (int c = 1; c < mapWidth - 1; c++) {
                    if (Math.random() < density) {
                        jedis.setbit(RedisKeys.MAP_BLOCK, RedisKeys.index(r, c), true);
                    }
                }
            }
        }
    }

    @Override
    public void clearAllObstacles() {
        try (Jedis jedis = getJedis()) {
            for (int i = 0; i < mapWidth * mapHeight; i++) {
                jedis.setbit(RedisKeys.MAP_BLOCK, i, false);
            }
        }
    }

    // ==================== 汽车位置 ====================
    @Override
    public void setPosition(String carId, int x, int y) {
        try (Jedis jedis = getJedis()) {
            jedis.hset(RedisKeys.positionKey(carId), "x", String.valueOf(x));
            jedis.hset(RedisKeys.positionKey(carId), "y", String.valueOf(y));
        }
    }

    @Override
    public Map<String, String> getPosition(String carId) {
        try (Jedis jedis = getJedis()) {
            return jedis.hgetAll(RedisKeys.positionKey(carId));
        }
    }

    // ==================== 目标 ====================
    @Override
    public void setTarget(String carId, int x, int y) {
        try (Jedis jedis = getJedis()) {
            jedis.set(RedisKeys.targetKey(carId), "{\"x\":" + x + ",\"y\":" + y + "}");
        }
    }

    @Override
    public Map<String, String> getTarget(String carId) {
        try (Jedis jedis = getJedis()) {
            String json = jedis.get(RedisKeys.targetKey(carId));
            if (json == null) return null;
            // 简易解析
            json = json.replace("{", "").replace("}", "").replace("\"", "");
            String[] parts = json.split(",");
            String x = parts[0].split(":")[1];
            String y = parts[1].split(":")[1];
            return Map.of("x", x, "y", y);
        }
    }

    @Override
    public void clearTarget(String carId) {
        try (Jedis jedis = getJedis()) {
            jedis.del(RedisKeys.targetKey(carId));
        }
    }

    // ==================== 路径 ====================
    @Override
    public void pushRoute(String carId, String json) {
        try (Jedis jedis = getJedis()) {
            jedis.lpush(RedisKeys.routeListKey(carId), json);
        }
    }

    @Override
    public String popRoute(String carId) {
        try (Jedis jedis = getJedis()) {
            return jedis.rpop(RedisKeys.routeListKey(carId));
        }
    }

    @Override
    public String peekRoute(String carId) {
        try (Jedis jedis = getJedis()) {
            List<String> list = jedis.lrange(RedisKeys.routeListKey(carId), -1, -1);
            return list.isEmpty() ? null : list.get(0);
        }
    }

    @Override
    public void clearRoute(String carId) {
        try (Jedis jedis = getJedis()) {
            jedis.del(RedisKeys.routeListKey(carId));
        }
    }

    @Override
    public long getRouteLength(String carId) {
        try (Jedis jedis = getJedis()) {
            return jedis.llen(RedisKeys.routeListKey(carId));
        }
    }

    // ==================== 状态 ====================
    @Override
    public void setStatus(String carId, String status) {
        try (Jedis jedis = getJedis()) {
            jedis.set(RedisKeys.statusKey(carId), status);
        }
    }

    @Override
    public String getStatus(String carId) {
        try (Jedis jedis = getJedis()) {
            return jedis.get(RedisKeys.statusKey(carId));
        }
    }

    // ==================== 步数 ====================
    @Override
    public int getSteps(String carId) {
        try (Jedis jedis = getJedis()) {
            String val = jedis.get(RedisKeys.stepsKey(carId));
            return val == null ? 0 : Integer.parseInt(val);
        }
    }

    @Override
    public void incrementSteps(String carId) {
        try (Jedis jedis = getJedis()) {
            jedis.incr(RedisKeys.stepsKey(carId));
        }
    }

    // ==================== 受阻 ====================
    @Override
    public void setBlockedTick(String carId, long tick) {
        try (Jedis jedis = getJedis()) {
            jedis.set(RedisKeys.blockedTickKey(carId), String.valueOf(tick));
        }
    }

    @Override
    public long getBlockedTick(String carId) {
        try (Jedis jedis = getJedis()) {
            String val = jedis.get(RedisKeys.blockedTickKey(carId));
            return val == null ? 0 : Long.parseLong(val);
        }
    }

    // ==================== 配置 ====================
    @Override
    public void setTaskConfig(Map<String, String> config) {
        try (Jedis jedis = getJedis()) {
            jedis.hset(RedisKeys.TASK_CONFIG, config);
        }
    }

    @Override
    public Map<String, String> getTaskConfig() {
        try (Jedis jedis = getJedis()) {
            return jedis.hgetAll(RedisKeys.TASK_CONFIG);
        }
    }

    // ==================== 快照与统计 ====================
    @Override
    public void saveSnapshot(String json) {
        try (Jedis jedis = getJedis()) {
            jedis.lpush(RedisKeys.REPLAY_SNAPSHOTS, json);
        }
    }

    @Override
    public List<String> getAllSnapshots() {
        try (Jedis jedis = getJedis()) {
            long len = jedis.llen(RedisKeys.REPLAY_SNAPSHOTS);
            if (len == 0) return List.of();
            return jedis.lrange(RedisKeys.REPLAY_SNAPSHOTS, 0, len - 1);
        }
    }

    @Override
    public int getSnapshotCount() {
        try (Jedis jedis = getJedis()) {
            return (int) (long) jedis.llen(RedisKeys.REPLAY_SNAPSHOTS);
        }
    }

    @Override
    public void setStatsReport(String json) {
        try (Jedis jedis = getJedis()) {
            jedis.set(RedisKeys.STATS_REPORT, json);
        }
    }

    @Override
    public String getStatsReport() {
        try (Jedis jedis = getJedis()) {
            return jedis.get(RedisKeys.STATS_REPORT);
        }
    }

    // ==================== 注册 ====================
    @Override
    public void registerCar(String carId) {
        try (Jedis jedis = getJedis()) {
            jedis.sadd(RedisKeys.REGISTRY_CARS, carId);
        }
    }

    @Override
    public List<String> getOnlineCars() {
        try (Jedis jedis = getJedis()) {
            return new ArrayList<>(jedis.smembers(RedisKeys.REGISTRY_CARS));
        }
    }

    @Override
    public void unregisterCar(String carId) {
        try (Jedis jedis = getJedis()) {
            jedis.srem(RedisKeys.REGISTRY_CARS, carId);
        }
    }

    @Override
    public long getCarCount() {
        try (Jedis jedis = getJedis()) {
            return jedis.scard(RedisKeys.REGISTRY_CARS);
        }
    }

    // ==================== 日志 ====================
    @Override
    public void addLogEntry(String entry) {
        try (Jedis jedis = getJedis()) {
            jedis.lpush(RedisKeys.EXPLORATION_LOG, entry);
            // 只保留最近 1000 条
            jedis.ltrim(RedisKeys.EXPLORATION_LOG, 0, 999);
        }
    }

    @Override
    public List<String> getLogs(int count) {
        try (Jedis jedis = getJedis()) {
            long len = jedis.llen(RedisKeys.EXPLORATION_LOG);
            if (len == 0) return List.of();
            long end = Math.min(count - 1, len - 1);
            return jedis.lrange(RedisKeys.EXPLORATION_LOG, 0, end);
        }
    }

    // ==================== 用户管理 ====================
    @Override
    public String createUser(String nickname) {
        try (Jedis jedis = getJedis()) {
            long id = jedis.incr(RedisKeys.USER_ID_COUNTER);
            String userId = "U" + id;
            jedis.set(RedisKeys.userNameKey(userId), nickname);
            return userId;
        }
    }

    @Override
    public void setCurrentUser(String userId) {
        try (Jedis jedis = getJedis()) {
            jedis.set(RedisKeys.USER_CURRENT_ID, userId);
        }
    }

    @Override
    public String getCurrentUser() {
        try (Jedis jedis = getJedis()) {
            return jedis.get(RedisKeys.USER_CURRENT_ID);
        }
    }

    @Override
    public String getUserNickname(String userId) {
        try (Jedis jedis = getJedis()) {
            return jedis.get(RedisKeys.userNameKey(userId));
        }
    }

    @Override
    public void setUserPref(String userId, String key, String value) {
        try (Jedis jedis = getJedis()) {
            jedis.hset(RedisKeys.userPrefsKey(userId), key, value);
        }
    }

    @Override
    public Map<String, String> getUserPrefs(String userId) {
        try (Jedis jedis = getJedis()) {
            return jedis.hgetAll(RedisKeys.userPrefsKey(userId));
        }
    }

    @Override
    public void addUserHistory(String userId, String record) {
        try (Jedis jedis = getJedis()) {
            jedis.lpush(RedisKeys.userHistoryKey(userId), record);
            jedis.ltrim(RedisKeys.userHistoryKey(userId), 0, 49); // 保留50条
        }
    }

    @Override
    public List<String> getUserHistory(String userId) {
        try (Jedis jedis = getJedis()) {
            long len = jedis.llen(RedisKeys.userHistoryKey(userId));
            if (len == 0) return List.of();
            return jedis.lrange(RedisKeys.userHistoryKey(userId), 0, len - 1);
        }
    }

    // ==================== 原子移动 ====================
    @Override
    public void atomicMove(String carId, int oldX, int oldY, int newX, int newY, int visionRadius) {
        try (Jedis jedis = getJedis()) {
            Transaction t = jedis.multi();

            // 更新位置
            t.hset(RedisKeys.positionKey(carId), "x", String.valueOf(newX));
            t.hset(RedisKeys.positionKey(carId), "y", String.valueOf(newY));

            // 旧位置清除障碍
            t.setbit(RedisKeys.MAP_BLOCK, RedisKeys.index(oldY, oldX), false);

            // 新位置设置障碍
            t.setbit(RedisKeys.MAP_BLOCK, RedisKeys.index(newY, newX), true);

            // 点亮视野范围
            for (int dr = -visionRadius; dr <= visionRadius; dr++) {
                for (int dc = -visionRadius; dc <= visionRadius; dc++) {
                    int vr = newY + dr;
                    int vc = newX + dc;
                    if (vr >= 0 && vr < mapHeight && vc >= 0 && vc < mapWidth) {
                        t.setbit(RedisKeys.MAP_VIEW, RedisKeys.index(vr, vc), true);
                    }
                }
            }

            t.exec();
        }
    }

    // ==================== 工具 ====================
    @Override
    public double getExploredPercent() {
        try (Jedis jedis = getJedis()) {
            long explored = jedis.bitcount(RedisKeys.MAP_VIEW);
            return (double) explored / (mapWidth * mapHeight) * 100.0;
        }
    }

    @Override
    public void clearAll() {
        try (Jedis jedis = getJedis()) {
            jedis.flushDB();
        }
    }

    @Override
    public void close() {
        pool.close();
    }
}