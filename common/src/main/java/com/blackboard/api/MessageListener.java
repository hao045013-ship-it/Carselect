package com.blackboard.api;

/**
 * 消息回调接口 —— 收到 MQ 消息时触发
 */
public interface MessageListener {
    void onMessage(String message);
}