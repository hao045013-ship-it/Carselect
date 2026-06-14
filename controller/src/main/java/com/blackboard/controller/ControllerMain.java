package com.blackboard.controller;

import com.blackboard.api.Blackboard;
import com.blackboard.api.MessageQueue;
import com.blackboard.api.impl.BlackboardImpl;
import com.blackboard.api.impl.MessageQueueImpl;

public class ControllerMain {

    public static void main(String[] args) {
        Blackboard board = new BlackboardImpl("localhost", 6379);

        MessageQueue mq = new MessageQueueImpl("localhost", 5672);
        mq.connect();

        ControllerAgent controller = new ControllerAgent(board, mq);
        controller.start();

        System.out.println("Controller started.");
    }
}