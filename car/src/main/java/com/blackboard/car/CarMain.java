package com.blackboard.car;
import com.blackboard.api.Blackboard;
import com.blackboard.api.MessageQueue;
import com.blackboard.api.impl.BlackboardImpl;
import com.blackboard.api.impl.MessageQueueImpl;

public class CarMain {

    public static void main(String[] args) {
        String carId = args.length > 0 ? args[0] : "Car001";

        Blackboard board = new BlackboardImpl("localhost", 6379);

        MessageQueue mq = new MessageQueueImpl("localhost", 5672);
        mq.connect();

        CarAgent agent = new CarAgent(carId, board, mq);
        agent.start();

        System.out.println(carId + " started.");
    }
}
