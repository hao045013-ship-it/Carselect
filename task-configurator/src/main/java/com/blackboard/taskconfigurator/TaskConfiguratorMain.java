package com.blackboard.taskconfigurator;

import com.blackboard.api.Blackboard;
import com.blackboard.api.MessageQueue;
import com.blackboard.api.impl.BlackboardImpl;
import com.blackboard.api.impl.MessageQueueImpl;

public class TaskConfiguratorMain {

    public static void main(String[] args) {
        Blackboard board = new BlackboardImpl("localhost", 6379);

        MessageQueue mq = new MessageQueueImpl("localhost", 5672);
        mq.connect();

        TaskConfiguratorAgent agent = new TaskConfiguratorAgent(board, mq);
        agent.start();

        System.out.println("TaskConfigurator started.");//
    }
}
