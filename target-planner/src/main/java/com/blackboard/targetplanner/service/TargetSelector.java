package com.blackboard.targetplanner.service;

/**
 * 目标选择策略接口。
 *
 * <p>TargetPlannerService 只依赖该接口，因此后续可以新增目标选择算法，
 * 不需要改 RabbitMQ 消息格式和 Redis 目标写入格式。</p>
 */
public interface TargetSelector {
    TargetSelectionDecision select(TargetSelectionContext context);
}
