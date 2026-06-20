package com.blackboard.navigator.util;

import com.blackboard.api.Blackboard;
import com.blackboard.model.Position;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Navigator 的黑板读取辅助类。
 *
 * <p>Navigator 只读取“已经探索过”的静态障碍物：先读取全局 mapView，只有某个格子已经被任意小车
 * 探索过时，才查询该格子的 staticBlock/mapBlock。这样所有小车都能共享已探索到的障碍物，
 * 但未探索区域中的障碍物仍然保持未知，不会被路径规划提前使用。</p>
 */
public class NavigatorDataReader {
    private final Blackboard board;

    public NavigatorDataReader(Blackboard board) {
        this.board = board;
    }

    public Position readPosition(String carId) {
        Map<String, String> raw = board.getPosition(carId);
        if (raw == null || raw.get("x") == null || raw.get("y") == null) {
            throw new IllegalStateException("Missing position for " + carId);
        }
        return new Position(Integer.parseInt(raw.get("x")), Integer.parseInt(raw.get("y")));
    }

    public Position readTarget(String carId) {
        Map<String, String> raw = board.getTarget(carId);
        if (raw == null || raw.get("x") == null || raw.get("y") == null) {
            throw new IllegalStateException("Missing target for " + carId);
        }
        return new Position(Integer.parseInt(raw.get("x")), Integer.parseInt(raw.get("y")));
    }

    public Set<Position> readOtherCarPositions(String currentCarId) {
        Set<Position> blocks = new HashSet<>();
        for (String carId : readCarIds()) {
            if (carId.equals(currentCarId)) continue;
            try {
                blocks.add(readPosition(carId));
            } catch (RuntimeException ignored) {
                // 某辆车位置尚未初始化时，不让整个规划组件崩溃。
            }
        }
        return blocks;
    }

    /**
     * 读取所有已知阻塞格：已探索静态障碍 + 其他小车当前位置。
     *
     * <p>注意：这里不会读取未探索格子的障碍状态，因此不会把完整地图中的隐藏障碍提前暴露给小车。</p>
     */
    public Set<Position> readKnownBlockedCells(String currentCarId, int mapWidth, int mapHeight) {
        Set<Position> result = readKnownStaticObstacles(mapWidth, mapHeight);
        result.addAll(readOtherCarPositions(currentCarId));
        return result;
    }

    /**
     * 只在已探索格子中查询静态障碍物。
     */
    public Set<Position> readKnownStaticObstacles(int mapWidth, int mapHeight) {
        Set<Position> result = new HashSet<>();
        boolean[] explored = board.getFullMapView();
        boolean[] staticBlocks = board.getFullStaticBlock();
        for (int y = 0; y < mapHeight; y++) {
            for (int x = 0; x < mapWidth; x++) {
                if (!isExplored(explored, x, y, mapWidth)) continue;
                if (isBlocked(staticBlocks, x, y, mapWidth)) {
                    result.add(new Position(x, y));
                }
            }
        }
        return result;
    }

    public List<String> readCarIds() {
        List<String> carIds = new ArrayList<>(board.getCarList());
        if (!carIds.isEmpty()) return carIds;

        Map<String, String> config = board.getTaskConfig();
        String countText = config == null ? null : config.get("carCount");
        if (countText == null) return carIds;

        int count = Integer.parseInt(countText);
        for (int i = 1; i <= count; i++) {
            carIds.add("Car" + String.format("%03d", i));
        }
        return carIds;
    }

    private boolean isExplored(boolean[] explored, int x, int y, int mapWidth) {
        if (explored == null) return false;
        int idx = y * mapWidth + x;
        return idx >= 0 && idx < explored.length && explored[idx];
    }

    private boolean isBlocked(boolean[] blocks, int x, int y, int mapWidth) {
        if (blocks == null) return false;
        int idx = y * mapWidth + x;
        return idx >= 0 && idx < blocks.length && blocks[idx];
    }
}
