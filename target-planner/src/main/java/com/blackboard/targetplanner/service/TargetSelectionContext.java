package com.blackboard.targetplanner.service;

import com.blackboard.model.Position;

import java.util.Collections;
import java.util.Set;

/**
 * 目标选择算法的输入上下文。
 *
 * <p>把算法需要的数据统一封装，后续新增策略时不需要改 Agent 和 Redis 读取逻辑。</p>
 */
public class TargetSelectionContext {
    private final String carId;
    private final Position carPosition;
    private final boolean[] explored;
    private final int mapWidth;
    private final int mapHeight;
    private final Set<Position> occupiedByCars;
    private final Set<Position> reservedTargets;
    private final Set<Position> knownObstacles;

    public TargetSelectionContext(String carId,
                                  Position carPosition,
                                  boolean[] explored,
                                  int mapWidth,
                                  int mapHeight,
                                  Set<Position> occupiedByCars,
                                  Set<Position> reservedTargets) {
        this(carId, carPosition, explored, mapWidth, mapHeight, occupiedByCars, reservedTargets, Collections.emptySet());
    }

    public TargetSelectionContext(String carId,
                                  Position carPosition,
                                  boolean[] explored,
                                  int mapWidth,
                                  int mapHeight,
                                  Set<Position> occupiedByCars,
                                  Set<Position> reservedTargets,
                                  Set<Position> knownObstacles) {
        this.carId = carId;
        this.carPosition = carPosition;
        this.explored = explored;
        this.mapWidth = mapWidth;
        this.mapHeight = mapHeight;
        this.occupiedByCars = occupiedByCars == null ? Collections.emptySet() : occupiedByCars;
        this.reservedTargets = reservedTargets == null ? Collections.emptySet() : reservedTargets;
        this.knownObstacles = knownObstacles == null ? Collections.emptySet() : knownObstacles;
    }

    public String getCarId() {
        return carId;
    }

    public Position getCarPosition() {
        return carPosition;
    }

    public boolean[] getExplored() {
        return explored;
    }

    public int getMapWidth() {
        return mapWidth;
    }

    public int getMapHeight() {
        return mapHeight;
    }

    public Set<Position> getOccupiedByCars() {
        return occupiedByCars;
    }

    public Set<Position> getReservedTargets() {
        return reservedTargets;
    }

    public Set<Position> getKnownObstacles() {
        return knownObstacles;
    }
}
