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
 * <p>这里刻意不读取 staticBlock/mapBlock，保证目标选择模块不提前知道静态障碍物。</p>
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
}
