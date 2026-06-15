package com.blackboard.navigator.path;

import com.blackboard.model.Position;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/**
 * BFS 网格最短路。
 *
 * <p>适用于四方向、每步代价相同的地图。按照当前需求，本算法不读取静态障碍物，
 * 只把其他小车当前位置看作动态障碍物。</p>
 */
public class BfsPathFinder extends AbstractGridPathFinder {

    @Override
    public List<Position> findPath(Position start, Position target, int mapWidth, int mapHeight, Set<Position> blockedCells) {
        return findPathWithMetrics(start, target, mapWidth, mapHeight, blockedCells).getPath();
    }

    @Override
    public PathSearchResult findPathWithMetrics(Position start,
                                                Position target,
                                                int mapWidth,
                                                int mapHeight,
                                                Set<Position> blockedCells) {
        long begin = System.nanoTime();
        if (start == null || target == null) return PathSearchResult.notFound(0, elapsedMillis(begin));
        if (!inBounds(start.getX(), start.getY(), mapWidth, mapHeight)) return PathSearchResult.notFound(0, elapsedMillis(begin));
        if (!inBounds(target.getX(), target.getY(), mapWidth, mapHeight)) return PathSearchResult.notFound(0, elapsedMillis(begin));
        if (same(start, target)) return new PathSearchResult(Collections.emptyList(), true, 1, elapsedMillis(begin));
        if (blockedCells.contains(target)) return PathSearchResult.notFound(0, elapsedMillis(begin));

        boolean[][] visited = new boolean[mapHeight][mapWidth];
        Position[][] parent = new Position[mapHeight][mapWidth];
        Queue<Position> queue = new ArrayDeque<>();
        int visitedNodes = 0;

        visited[start.getY()][start.getX()] = true;
        queue.offer(new Position(start.getX(), start.getY()));

        while (!queue.isEmpty()) {
            Position current = queue.poll();
            visitedNodes++;
            if (same(current, target)) {
                List<Position> path = buildPath(parent, start, target);
                return new PathSearchResult(path, true, visitedNodes, elapsedMillis(begin));
            }

            for (int[] d : DIRECTIONS) {
                int nx = current.getX() + d[0];
                int ny = current.getY() + d[1];
                if (!inBounds(nx, ny, mapWidth, mapHeight)) continue;
                if (visited[ny][nx]) continue;

                Position next = new Position(nx, ny);
                // 起点是当前车自己的位置，允许进入；其他动态障碍不能进入。
                if (blockedCells.contains(next) && !same(next, start)) continue;

                visited[ny][nx] = true;
                parent[ny][nx] = current;
                queue.offer(next);
            }
        }
        return PathSearchResult.notFound(visitedNodes, elapsedMillis(begin));
    }

    private long elapsedMillis(long beginNano) {
        return (System.nanoTime() - beginNano) / 1_000_000;
    }
}
