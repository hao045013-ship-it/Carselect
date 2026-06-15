package com.blackboard.navigator.path;

import com.blackboard.model.Position;
import java.util.List;
import java.util.Set;

/**
 * 路径规划算法接口。
 *
 * <p>NavigatorAgent 只依赖这个接口，因此后续可以新增 Dijkstra、Theta*、Weighted A* 等算法，
 * 不需要改动消息监听和黑板读写代码。</p>
 */
public interface PathFinder {

    /**
     * 规划从 start 到 target 的路径。
     * 返回的路径不包含起点，包含终点；如果不可达则返回空列表。
     *
     * @param start 起点，Position.x 表示列，Position.y 表示行
     * @param target 终点，Position.x 表示列，Position.y 表示行
     * @param mapWidth 地图宽度
     * @param mapHeight 地图高度
     * @param blockedCells 当前规划时刻不可进入的动态障碍物集合
     */
    default List<Position> findPath(Position start, Position target, int mapWidth, int mapHeight, Set<Position> blockedCells) {
        return findPathWithMetrics(start, target, mapWidth, mapHeight, blockedCells).getPath();
    }

    /**
     * 带统计信息的路径规划。默认实现只统计耗时，具体算法可重写以统计访问节点数。
     */
    default PathSearchResult findPathWithMetrics(Position start,
                                                 Position target,
                                                 int mapWidth,
                                                 int mapHeight,
                                                 Set<Position> blockedCells) {
        long begin = System.nanoTime();
        List<Position> path = findPath(start, target, mapWidth, mapHeight, blockedCells);
        long elapsed = (System.nanoTime() - begin) / 1_000_000;
        boolean found = start != null && target != null && (start.equals(target) || !path.isEmpty());
        return new PathSearchResult(path, found, -1, elapsed);
    }
}
