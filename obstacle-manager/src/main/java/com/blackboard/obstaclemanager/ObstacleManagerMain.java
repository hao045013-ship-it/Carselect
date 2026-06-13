package com.blackboard.obstaclemanager;

import com.blackboard.api.Blackboard;
import com.blackboard.api.MessageQueue;
import com.blackboard.api.impl.BlackboardImpl;
import com.blackboard.api.impl.MessageQueueImpl;

public class ObstacleManagerMain {
    public static void main(String[] args) {
        Blackboard board = new BlackboardImpl("localhost", 6379);
        MessageQueue mq = new MessageQueueImpl("localhost", 5672);
        mq.connect();

        // 可选：显式声明公共队列（如果 subscribe 内部已自动声明，可省略）
        mq.declareAllQueues(0);

        ObstacleManagerAgent agent = new ObstacleManagerAgent(board, mq);
        agent.start();

        System.out.println("ObstacleManager started.");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            mq.close();
            board.close();
        }));
    }
}