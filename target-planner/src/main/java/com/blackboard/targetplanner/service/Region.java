package com.blackboard.targetplanner.service;

import com.blackboard.model.Position;

/**
 * 地图软分区。边界采用左闭右开、上闭下开，便于按网格切块。
 */
public class Region {
    private final int minX;
    private final int minY;
    private final int maxXExclusive;
    private final int maxYExclusive;

    public Region(int minX, int minY, int maxXExclusive, int maxYExclusive) {
        this.minX = minX;
        this.minY = minY;
        this.maxXExclusive = maxXExclusive;
        this.maxYExclusive = maxYExclusive;
    }

    public boolean contains(Position position) {
        return position != null
                && position.getX() >= minX
                && position.getX() < maxXExclusive
                && position.getY() >= minY
                && position.getY() < maxYExclusive;
    }
}
