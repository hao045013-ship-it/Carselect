package com.blackboard.targetplanner.util;

import com.blackboard.api.Blackboard;
import com.blackboard.model.Position;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TargetPlanner 的黑板读取辅助类。
 *
 * <p>目标选择模块只读取已探索格子里的静态障碍物。具体做法是先看全局 mapView，只有某格已经被任意小车
 * 探索过，才查询该格是否为障碍物。这样所有小车共享已经探索到的障碍信息，未探索区域仍保持未知。</p>
 */
public class TargetPlannerDataReader {
    private final Blackboard board;

    public TargetPlannerDataReader(Blackboard board) {
        this.board = board;
    }

    public Position readPosition(String carId) {
        Map<String, String> raw = board.getPosition(carId);
        if (raw == null || raw.get("x") == null || raw.get("y") == null) {
            throw new IllegalStateException("Missing position for " + carId);
        }
        return new Position(Integer.parseInt(raw.get("x")), Integer.parseInt(raw.get("y")));
    }

    public Set<Position> readAllCarPositions() {
        Set<Position> result = new HashSet<>();
        for (String carId : readCarIds()) {
            try {
                result.add(readPosition(carId));
            } catch (RuntimeException ignored) {
                // 忽略未初始化车辆，避免单车异常导致目标分配组件退出。
            }
        }
        return result;
    }

    public Set<Position> readReservedTargets(String currentCarId) {
        Set<Position> result = new HashSet<>();
        for (String carId : readCarIds()) {
            if (carId.equals(currentCarId)) continue;
            Map<String, String> raw = board.getTarget(carId);
            if (raw == null || raw.get("x") == null || raw.get("y") == null) continue;
            result.add(new Position(Integer.parseInt(raw.get("x")), Integer.parseInt(raw.get("y"))));
        }
        return result;
    }

    /**
     * 读取已知静态障碍物：只在已经探索过的格子里检查障碍。
     */
    public Set<Position> readKnownStaticObstacles(int mapWidth, int mapHeight) {
        Set<Position> result = new HashSet<>();
        boolean[] explored = board.getFullMapView();
        for (int y = 0; y < mapHeight; y++) {
            for (int x = 0; x < mapWidth; x++) {
                if (!isExplored(explored, x, y, mapWidth)) continue;
                if (board.hasObstacle(y, x)) {
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
}
