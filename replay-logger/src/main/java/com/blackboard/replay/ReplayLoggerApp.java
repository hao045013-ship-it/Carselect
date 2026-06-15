package com.blackboard.replay;

import com.blackboard.api.Blackboard;
import com.blackboard.api.MessageQueue;
import com.blackboard.api.impl.BlackboardImpl;
import com.blackboard.api.impl.MessageQueueImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 路径回放服务启动入口
 * <p>
 * 初始化流程：
 * 1. 创建 BlackboardImpl 和 MessageQueueImpl（host/port 从 application.yml 读取）
 * 2. mq.connect() 和 mq.declareAllQueues(5)
 * 3. 启动 SnapshotRecorder 开始订阅
 * 4. 启动 Spring Boot HTTP 服务（端口 8084）
 */
@SpringBootApplication
public class ReplayLoggerApp {

    public static void main(String[] args) {
        SpringApplication.run(ReplayLoggerApp.class, args);
        System.out.println("[ReplayLogger] HTTP 回放服务已启动，端口 8084");
    }

    @Configuration
    static class ReplayConfig {

        @Bean
        Blackboard blackboard(
                @Value("${redis.host}") String host,
                @Value("${redis.port}") int port) {
            return new BlackboardImpl(host, port);
        }

        @Bean
        MessageQueue messageQueue(
                @Value("${rabbitmq.host}") String host,
                @Value("${rabbitmq.port}") int port) {
            MessageQueueImpl mq = new MessageQueueImpl(host, port);
            mq.connect();
            mq.declareAllQueues(5);
            return mq;
        }

        @Bean
        SqlReplayPersistence sqlReplayPersistence(
                @Value("${sqlserver.url}") String url,
                @Value("${sqlserver.username}") String username,
                @Value("${sqlserver.password}") String password) {
            return new SqlReplayPersistence(url, username, password);
        }

        @Bean
        SnapshotRecorder snapshotRecorder(
                Blackboard board,
                MessageQueue mq,
                SqlReplayPersistence sqlReplayPersistence) {
            SnapshotRecorder recorder = new SnapshotRecorder(board, mq, sqlReplayPersistence);
            recorder.start();
            return recorder;
        }
    }
}
