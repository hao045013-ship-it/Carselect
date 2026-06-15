package com.blackboard.navigator.path;

import com.blackboard.model.Position;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * A* 网格路径规划。
 *
 * <p>启发函数使用曼哈顿距离；在四方向、单步代价相同的网格中，默认 A* 的启发函数可采纳，
 * 因此找到的路径长度通常与 BFS 一致，但访问节点更少。</p>
 */
public class AStarPathFinder extends AbstractGridPathFinder {

    private final double heuristicWeight;
    private final int nearDynamicObstaclePenalty;

    public AStarPathFinder() {
        this(1.0, 0);
    }

    protected AStarPathFinder(double heuristicWeight, int nearDynamicObstaclePenalty) {
        this.heuristicWeight = heuristicWeight <= 0 ? 1.0 : heuristicWeight;
        this.nearDynamicObstaclePenalty = Math.max(0, nearDynamicObstaclePenalty);
    }

    private static class Node {
        private final Position position;
        private final double g;
        private final double f;

        private Node(Position position, double g, double f) {
            this.position = position;
            this.g = g;
            this.f = f;
        }
    }

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

        double[][] bestG = new double[mapHeight][mapWidth];
        for (double[] row : bestG) {
            Arrays.fill(row, Double.POSITIVE_INFINITY);
        }
        boolean[][] closed = new boolean[mapHeight][mapWidth];
        Position[][] parent = new Position[mapHeight][mapWidth];

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator
                .comparingDouble((Node n) -> n.f)
                .thenComparingDouble(n -> n.g));

        bestG[start.getY()][start.getX()] = 0;
        open.offer(new Node(new Position(start.getX(), start.getY()), 0, heuristic(start, target)));
        int visitedNodes = 0;

        while (!open.isEmpty()) {
            Node node = open.poll();
            Position current = node.position;
            if (closed[current.getY()][current.getX()]) continue;
            closed[current.getY()][current.getX()] = true;
            visitedNodes++;

            if (same(current, target)) {
                List<Position> path = buildPath(parent, start, target);
                return new PathSearchResult(path, true, visitedNodes, elapsedMillis(begin));
            }

            for (int[] d : DIRECTIONS) {
                int nx = current.getX() + d[0];
                int ny = current.getY() + d[1];
                if (!inBounds(nx, ny, mapWidth, mapHeight)) continue;
                if (closed[ny][nx]) continue;

                Position next = new Position(nx, ny);
                if (blockedCells.contains(next) && !same(next, start)) continue;

                double stepCost = 1.0 + softDynamicObstacleCost(next, blockedCells);
                double tentativeG = node.g + stepCost;
                if (tentativeG >= bestG[ny][nx]) continue;

                bestG[ny][nx] = tentativeG;
                parent[ny][nx] = current;
                double f = tentativeG + heuristic(next, target);
                open.offer(new Node(next, tentativeG, f));
            }
        }
        return PathSearchResult.notFound(visitedNodes, elapsedMillis(begin));
    }

    private double heuristic(Position current, Position target) {
        return heuristicWeight * manhattan(current, target);
    }

    /**
     * 动态障碍物软避让：障碍物本身不可走；障碍物周围一格可走，但在增强算法中会增加代价。
     * 普通 A* 的 nearDynamicObstaclePenalty 为 0，因此不改变原有最短路径语义。
     */
    private int softDynamicObstacleCost(Position candidate, Set<Position> blockedCells) {
        if (nearDynamicObstaclePenalty <= 0 || blockedCells == null || blockedCells.isEmpty()) return 0;
        for (Position blocked : blockedCells) {
            if (candidate.manhattanDistance(blocked) == 1) {
                return nearDynamicObstaclePenalty;
            }
        }
        return 0;
    }

    private long elapsedMillis(long beginNano) {
        return (System.nanoTime() - beginNano) / 1_000_000;
    }
}
