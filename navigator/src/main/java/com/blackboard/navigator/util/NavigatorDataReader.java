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
 * <p>这里刻意不读取 staticBlock/mapBlock，保证 Navigator 不提前知道静态障碍物。</p>
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
