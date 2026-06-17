package com.blackboard.api.impl;

import com.blackboard.api.Blackboard;
import com.blackboard.constant.RedisKeys;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Transaction;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 黑板实现类 —— 封装所有 Redis 操作
 *
 * 人1开发，其他人通过 Blackboard 接口使用
 */
public class BlackboardImpl implements Blackboard {

    private final JedisPool pool;

    public BlackboardImpl(String host, int port) {
        this.pool = new JedisPool(host, port);
    }

    // ==================== 动态获取地图尺寸 ====================
    private int getWidth(Jedis jedis) {
        String val = jedis.hget(RedisKeys.TASK_CONFIG, "mapWidth");
        return val == null ? RedisKeys.DEFAULT_MAP_WIDTH : Integer.parseInt(val);
    }

    private int getHeight(Jedis jedis) {
        String val = jedis.hget(RedisKeys.TASK_CONFIG, "mapHeight");
        return val == null ? RedisKeys.DEFAULT_MAP_HEIGHT : Integer.parseInt(val);
    }

    // 辅助方法：计算线性索引（带地图宽度）
    private int index(int row, int col, int width) {
        return row * width + col;
    }

    private byte[] redisKeyBytes(String key) {
        return key.getBytes(StandardCharsets.UTF_8);
    }

    private boolean[] readBitmap(Jedis jedis, String key, int total) {
        boolean[] result = new boolean[total];
        byte[] raw = jedis.get(redisKeyBytes(key));
        if (raw == null || raw.length == 0) {
            return result;
        }
        for (int i = 0; i < total; i++) {
            int byteIndex = i / 8;
            if (byteIndex >= raw.length) {
                break;
            }
            int bitIndex = 7 - (i % 8);
            result[i] = ((raw[byteIndex] >> bitIndex) & 1) == 1;
        }
        return result;
    }
    //新增
    private boolean isInMap(int row, int col, int width, int height) {
        return row >= 0 && row < height && col >= 0 && col < width;
    }

    private boolean hasStaticBlock(Jedis jedis, int row, int col, int width, int height) {
        return isInMap(row, col, width, height)
                && jedis.getbit(RedisKeys.STATIC_BLOCK, index(row, col, width));
    }

    private boolean isVisibleFrom(Jedis jedis,
                                  int centerRow,
                                  int centerCol,
                                  int targetRow,
                                  int targetCol,
                                  int width,
                                  int height) {
        if (!isInMap(targetRow, targetCol, width, height)) {
            return false;
        }

        int dr = targetRow - centerRow;
        int dc = targetCol - centerCol;
        if (dr == 0 && dc == 0) {
            return true;
        }

        // 半径为 1 时，斜向格不能穿过两个正交障碍的夹角。
        if (Math.abs(dr) == 1 && Math.abs(dc) == 1) {
            boolean verticalBlocked = hasStaticBlock(jedis, centerRow + dr, centerCol, width, height);
            boolean horizontalBlocked = hasStaticBlock(jedis, centerRow, centerCol + dc, width, height);
            return !(verticalBlocked && horizontalBlocked);
        }

        return true;
    }
    //
    // ==================== 获取连接 ====================
    private Jedis getJedis() {
        return pool.getResource();
    }

    // ==================== 地图尺寸接口（新增） ====================
    @Override
    public int getMapWidth() {
        try (Jedis jedis = getJedis()) {
            return getWidth(jedis);
        }
    }

    @Override
    public int getMapHeight() {
        try (Jedis jedis = getJedis()) {
            return getHeight(jedis);
        }
    }

    // ==================== 地图视野 ====================
    @Override
    public void exploreCell(int row, int col) {
        try (Jedis jedis = getJedis()) {
            int width = getWidth(jedis);
            jedis.setbit(RedisKeys.MAP_VIEW, index(row, col, width), true);
        }
    }

    @Override
    public boolean isExplored(int row, int col) {
        try (Jedis jedis = getJedis()) {
            int width = getWidth(jedis);
            return jedis.getbit(RedisKeys.MAP_VIEW, index(row, col, width));
        }
    }

    @Override
    public boolean[] getFullMapView() {
        try (Jedis jedis = getJedis()) {
            int width = getWidth(jedis);
            int height = getHeight(jedis);
            return readBitmap(jedis, RedisKeys.MAP_VIEW, width * height);
        }
    }

    // ==================== 静态障碍物（新增） ====================
    @Override
    public void setStaticBlock(int row, int col, boolean value) {
        try (Jedis jedis = getJedis()) {
            int width = getWidth(jedis);
            jedis.setbit(RedisKeys.STATIC_BLOCK, index(row, col, width), value);
        }
    }

    @Override
    public boolean hasStaticBlock(int row, int col) {
        try (Jedis jedis = getJedis()) {
            int width = getWidth(jedis);
            return jedis.getbit(RedisKeys.STATIC_BLOCK, index(row, col, width));
        }
    }

    @Override
    public boolean[] getFullStaticBlock() {
        try (Jedis jedis = getJedis()) {
            int width = getWidth(jedis);
            int height = getHeight(jedis);
            return readBitmap(jedis, RedisKeys.STATIC_BLOCK, width * height);
        }
    }

    @Override
    public void randomStaticBlocks(double density) {
        try (Jedis jedis = getJedis()) {
            int width = getWidth(jedis);
            int height = getHeight(jedis);
            // 清除所有静态障碍
            jedis.del(RedisKeys.STATIC_BLOCK);
            Pipeline pipeline = jedis.pipelined();
            Random random = new Random();
            // 随机生成（避开边界）
            for (int r = 1; r < height - 1; r++) {
                for (int c = 1; c < width - 1; c++) {
                    if (random.nextDouble() < density) {
                        pipeline.setbit(RedisKeys.STATIC_BLOCK, index(r, c, width), true);
                    }
                }
            }
            pipeline.sync();
        }
    }

    @Override
    public void clearStaticBlocks() {
        try (Jedis jedis = getJedis()) {
            jedis.del(RedisKeys.STATIC_BLOCK);
        }
    }

    // ==================== 动态障碍物（新增） ====================
    @Override
    public void setDynamicBlock(int row, int col, boolean value) {
        try (Jedis jedis = getJedis()) {
            int width = getWidth(jedis);
            jedis.setbit(RedisKeys.DYNAMIC_BLOCK, index(row, col, width), value);
        }
    }

    @Override
    public boolean hasDynamicBlock(int row, int col) {
        try (Jedis jedis = getJedis()) {
            int width = getWidth(jedis);
            return jedis.getbit(RedisKeys.DYNAMIC_BLOCK, index(row, col, width));
        }
    }

    @Override
    public void clearDynamicBlocks() {
        try (Jedis jedis = getJedis()) {
            jedis.del(RedisKeys.DYNAMIC_BLOCK);
        }
    }

    @Override
    public boolean[] getFullDynamicBlock() {
        try (Jedis jedis = getJedis()) {
            int width = getWidth(jedis);
            int height = getHeight(jedis);
            return readBitmap(jedis, RedisKeys.DYNAMIC_BLOCK, width * height);
        }
    }

    // ==================== 综合障碍判断（新增） ====================
    @Override
    public boolean hasBlock(int row, int col) {
        return hasStaticBlock(row, col) || hasDynamicBlock(row, col);
    }

    // ==================== 原有障碍物接口（保留，内部操作 MAP_BLOCK） ====================
    @Override
    public void setObstacle(int row, int col, boolean value) {
        setStaticBlock(row, col, value);

    }

    @Override
    public boolean hasObstacle(int row, int col) {
        return hasStaticBlock(row, col);
    }

    @Override
    public boolean[] getFullMapBlock() {
        return getFullStaticBlock();
    }

    @Override
    public void randomObstacles(double density) {
        randomStaticBlocks(density);
    }

    @Override
    public void clearAllObstacles() {
        clearStaticBlocks();
    }

    // ==================== 车辆管理（新增） ====================
    @Override
    public void addCar(String carId) {
        try (Jedis jedis = getJedis()) {
            jedis.sadd(RedisKeys.REGISTRY_CARS, carId);
        }
    }

    @Override
    public void removeCar(String carId) {
        try (Jedis jedis = getJedis()) {
            jedis.srem(RedisKeys.REGISTRY_CARS, carId);
        }
    }

    @Override
    public List<String> getCarList() {
        try (Jedis jedis = getJedis()) {
            return new ArrayList<>(jedis.smembers(RedisKeys.REGISTRY_CARS));
        }
    }

    @Override
    public boolean carExists(String carId) {
        try (Jedis jedis = getJedis()) {
            return jedis.sismember(RedisKeys.REGISTRY_CARS, carId);
        }
    }

    @Override
    public boolean tryAddCar(String carId, int row, int col, String status) {
        try (Jedis jedis = getJedis()) {
            int width = getWidth(jedis);
            int height = getHeight(jedis);
            if (carId == null || carId.isBlank()
                    || row < 0 || row >= height
                    || col < 0 || col >= width) {
                return false;
            }

            int cellIndex = index(row, col, width);
            String positionKey = RedisKeys.positionKey(carId);
            String statusKey = RedisKeys.statusKey(carId);

            jedis.watch(RedisKeys.REGISTRY_CARS, RedisKeys.STATIC_BLOCK, RedisKeys.DYNAMIC_BLOCK, positionKey);

            boolean exists = jedis.sismember(RedisKeys.REGISTRY_CARS, carId);
            boolean blocked = jedis.getbit(RedisKeys.STATIC_BLOCK, cellIndex)
                    || jedis.getbit(RedisKeys.DYNAMIC_BLOCK, cellIndex);
            if (exists || blocked) {
                jedis.unwatch();
                return false;
            }

            Transaction tx = jedis.multi();
            tx.sadd(RedisKeys.REGISTRY_CARS, carId);
            tx.hset(positionKey, "x", String.valueOf(col));
            tx.hset(positionKey, "y", String.valueOf(row));
            tx.set(statusKey, status == null ? "IDLE" : status);
            tx.setbit(RedisKeys.DYNAMIC_BLOCK, cellIndex, true);
            return tx.exec() != null;
        }
    }

    // ==================== 原有车辆注册接口（保留） ====================
    @Override
    public void registerCar(String carId) {
        addCar(carId);
    }

    @Override
    public List<String> getOnlineCars() {
        return getCarList();
    }

    @Override
    public void unregisterCar(String carId) {
        removeCar(carId);
    }

    @Override
    public long getCarCount() {
        try (Jedis jedis = getJedis()) {
            return jedis.scard(RedisKeys.REGISTRY_CARS);
        }
    }

    // ==================== 汽车位置 ====================
    @Override
    public void setPosition(String carId, int row, int col) {
        try (Jedis jedis = getJedis()) {
            jedis.hset(RedisKeys.positionKey(carId), "x", String.valueOf(col));
            jedis.hset(RedisKeys.positionKey(carId), "y", String.valueOf(row));
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

    @Override
    public List<String> getRouteList(String carId) {
        try (Jedis jedis = getJedis()) {
            return jedis.lrange(RedisKeys.routeListKey(carId), 0, -1);
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

    // ==================== 轨迹记录（新增） ====================
    @Override
    public void appendTrace(String carId, long tick, int x, int y) {
        try (Jedis jedis = getJedis()) {
            String trace = tick + "," + x + "," + y;
            jedis.lpush(RedisKeys.traceKey(carId), trace);
            // 可选：限制轨迹长度，例如保留最近 1000 条
            jedis.ltrim(RedisKeys.traceKey(carId), 0, 999);
        }
    }

    @Override
    public List<String> getTrace(String carId) {
        try (Jedis jedis = getJedis()) {
            long len = jedis.llen(RedisKeys.traceKey(carId));
            if (len == 0) return List.of();
            return jedis.lrange(RedisKeys.traceKey(carId), 0, len - 1);
        }
    }

    // ==================== 统计信息（新增） ====================
    @Override
    public void incrementBlockedCount(String carId) {
        try (Jedis jedis = getJedis()) {
            jedis.incr(RedisKeys.blockedCountKey(carId));
        }
    }

    @Override
    public int getBlockedCount(String carId) {
        try (Jedis jedis = getJedis()) {
            String val = jedis.get(RedisKeys.blockedCountKey(carId));
            return val == null ? 0 : Integer.parseInt(val);
        }
    }

    @Override
    public void incrementRoutePlanCount(String carId) {
        try (Jedis jedis = getJedis()) {
            jedis.incr(RedisKeys.routePlanCountKey(carId));
        }
    }

    @Override
    public int getRoutePlanCount(String carId) {
        try (Jedis jedis = getJedis()) {
            String val = jedis.get(RedisKeys.routePlanCountKey(carId));
            return val == null ? 0 : Integer.parseInt(val);
        }
    }

    @Override
    public void saveCoverageHistory(long tick, double coverage) {
        try (Jedis jedis = getJedis()) {
            String record = tick + "," + coverage;
            jedis.lpush(RedisKeys.COVERAGE_HISTORY, record);
            // 保留最近 1000 条记录
            jedis.ltrim(RedisKeys.COVERAGE_HISTORY, 0, 999);
        }
    }

    @Override
    public List<String> getCoverageHistory() {
        try (Jedis jedis = getJedis()) {
            long len = jedis.llen(RedisKeys.COVERAGE_HISTORY);
            if (len == 0) return List.of();
            return jedis.lrange(RedisKeys.COVERAGE_HISTORY, 0, len - 1);
        }
    }

    // ==================== 配置 ====================
    @Override
    public void setTaskConfig(Map<String, String> config) {
        try (Jedis jedis = getJedis()) {
            for (Map.Entry<String, String> entry : config.entrySet()) {
                jedis.hset(RedisKeys.TASK_CONFIG, entry.getKey(), entry.getValue());
            }//
        }
    }

    @Override
    public Map<String, String> getTaskConfig() {
        try (Jedis jedis = getJedis()) {
            return jedis.hgetAll(RedisKeys.TASK_CONFIG);
        }
    }

    // ==================== 快照与统计（保留旧接口） ====================
    @Override
    public void saveSnapshot(String json) {
        try (Jedis jedis = getJedis()) {
            jedis.rpush(RedisKeys.REPLAY_SNAPSHOTS, json);
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

    // ==================== 日志 ====================
    @Override
    public void addLogEntry(String entry) {
        try (Jedis jedis = getJedis()) {
            jedis.lpush(RedisKeys.EXPLORATION_LOG, entry);
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
            jedis.ltrim(RedisKeys.userHistoryKey(userId), 0, 49);
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

    // ==================== 原子移动（重要修改） ====================
    @Override
    public boolean atomicMove(String carId, int oldX, int oldY, int newX, int newY, int visionRadius) {
        try (Jedis jedis = getJedis()) {
            int width = getWidth(jedis);
            int height = getHeight(jedis);
            int oldIndex = index(oldY, oldX, width);
            int newIndex = index(newY, newX, width);

            jedis.watch(RedisKeys.DYNAMIC_BLOCK, RedisKeys.STATIC_BLOCK, RedisKeys.positionKey(carId));
            Map<String, String> currentPosition = jedis.hgetAll(RedisKeys.positionKey(carId));
            boolean positionChanged = currentPosition == null
                    || !String.valueOf(oldX).equals(currentPosition.get("x"))
                    || !String.valueOf(oldY).equals(currentPosition.get("y"));
            boolean targetBlocked = jedis.getbit(RedisKeys.STATIC_BLOCK, newIndex)
                    || (newIndex != oldIndex && jedis.getbit(RedisKeys.DYNAMIC_BLOCK, newIndex));
            if (positionChanged || targetBlocked) {
                jedis.unwatch();
                return false;
            }

            Transaction t = jedis.multi();

            // 更新位置
            t.hset(RedisKeys.positionKey(carId), "x", String.valueOf(newX));
            t.hset(RedisKeys.positionKey(carId), "y", String.valueOf(newY));

            // 旧位置清除动态障碍（车辆占位）
            if (newIndex != oldIndex) {
                t.setbit(RedisKeys.DYNAMIC_BLOCK, oldIndex, false);
                // 新位置设置动态障碍
                t.setbit(RedisKeys.DYNAMIC_BLOCK, newIndex, true);
            }

            // 点亮视野范围（使用 MAP_VIEW）
            for (int dr = -visionRadius; dr <= visionRadius; dr++) {
                for (int dc = -visionRadius; dc <= visionRadius; dc++) {
                    int vr = newY + dr;
                    int vc = newX + dc;
                    if (vr >= 0 && vr < height && vc >= 0 && vc < width) {
                        t.setbit(RedisKeys.MAP_VIEW, index(vr, vc, width), true);
                    }
                }
            }
            t.incr(RedisKeys.stepsKey(carId));
            return t.exec() != null;
        }
    }

    // ==================== 工具 ====================
    @Override
    public double getExploredPercent() {
        try (Jedis jedis = getJedis()) {
            int width = getWidth(jedis);
            int height = getHeight(jedis);
            long explored = jedis.bitcount(RedisKeys.MAP_VIEW);
            return (double) explored / (width * height) * 100.0;
        }
    }
    //节拍
    @Override
    public long getCurrentTick() {
        try (Jedis jedis = getJedis()) {
            String val = jedis.get(RedisKeys.CURRENT_TICK);
            return val == null ? 0L : Long.parseLong(val);
        }
    }

    @Override
    public void setCurrentTick(long tick) {
        try (Jedis jedis = getJedis()) {
            jedis.set(RedisKeys.CURRENT_TICK, String.valueOf(tick));
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
