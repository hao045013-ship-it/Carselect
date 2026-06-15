package com.blackboard.navigator.path;

import com.blackboard.model.Position;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

abstract class AbstractGridPathFinder implements PathFinder {
    protected static final int[][] DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}
    };

    protected boolean inBounds(int x, int y, int mapWidth, int mapHeight) {
        return x >= 0 && x < mapWidth && y >= 0 && y < mapHeight;
    }

    protected List<Position> buildPath(Position[][] parent, Position start, Position target) {
        List<Position> path = new ArrayList<>();
        Position cur = target;
        while (cur != null && !same(cur, start)) {
            path.add(new Position(cur.getX(), cur.getY()));
            cur = parent[cur.getY()][cur.getX()];
        }
        Collections.reverse(path);
        return path;
    }

    protected boolean same(Position a, Position b) {
        return a != null && b != null && a.getX() == b.getX() && a.getY() == b.getY();
    }

    protected int manhattan(Position a, Position b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY());
    }
}
