package com.blackboard.car;

import com.blackboard.api.Blackboard;
import com.blackboard.api.impl.BlackboardImpl;

import java.util.concurrent.CountDownLatch;

public class CarFleetMain {

    public static void main(String[] args) throws Exception {
        String redisHost = env("REDIS_HOST", "localhost");
        int redisPort = Integer.parseInt(env("REDIS_PORT", "6379"));

        String rabbitHost = env("RABBITMQ_HOST", "localhost");
        int rabbitPort = Integer.parseInt(env("RABBITMQ_PORT", "5672"));

        Blackboard board = new BlackboardImpl(redisHost, redisPort);

        CarFleet fleet = new CarFleet(board, rabbitHost, rabbitPort);
        fleet.start();

        System.out.println("[CarFleetMain] running.");
        System.out.println("[CarFleetMain] Redis = " + redisHost + ":" + redisPort);
        System.out.println("[CarFleetMain] RabbitMQ = " + rabbitHost + ":" + rabbitPort);

        Runtime.getRuntime().addShutdownHook(new Thread(fleet::stop));

        new CountDownLatch(1).await();
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}