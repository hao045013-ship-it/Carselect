package com.blackboard.model;

/**
 * 路径规划算法枚举。
 *
 * <p>BFS 和 A_STAR 是原有接口；WEIGHTED_A_STAR 是增强算法，
 * 用于在验收/实验中比较“最短路径”和“更快搜索”的差异。</p>
 */
public enum RouteAlgorithm {
    BFS,
    A_STAR,
    WEIGHTED_A_STAR
}
