package com.blackboard.navigator.path;

import com.blackboard.model.Position;

import java.util.Collections;
import java.util.List;

/**
 * 一次路径搜索的结果与统计信息。
 *
 * <p>原来的 PathFinder 只返回路径。为了方便实验验收，这个类额外保存访问节点数、耗时等信息。
 * 这些信息只用于日志和性能对比，不改变 Redis 中原有的路径格式。</p>
 */
public class PathSearchResult {
    private final List<Position> path;
    private final boolean pathFound;
    private final int visitedNodes;
    private final long elapsedMillis;

    public PathSearchResult(List<Position> path, boolean pathFound, int visitedNodes, long elapsedMillis) {
        this.path = path == null ? Collections.emptyList() : List.copyOf(path);
        this.pathFound = pathFound;
        this.visitedNodes = visitedNodes;
        this.elapsedMillis = elapsedMillis;
    }

    public static PathSearchResult notFound(int visitedNodes, long elapsedMillis) {
        return new PathSearchResult(Collections.emptyList(), false, visitedNodes, elapsedMillis);
    }

    public List<Position> getPath() {
        return path;
    }

    public boolean isPathFound() {
        return pathFound;
    }

    public int getVisitedNodes() {
        return visitedNodes;
    }

    public long getElapsedMillis() {
        return elapsedMillis;
    }
}
