package com.blackboard.stats;

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
 * 统计分析服务启动入口
 * <p>
 * 初始化流程：
 * 1. 创建 BlackboardImpl 和 MessageQueueImpl（host/port 从 application.yml 读取）
 * 2. mq.connect() 和 mq.declareAllQueues(5)
 * 3. 创建 SqlStatsPersistence 和 PredictionEngine
 * 4. 启动 StatsCollector 开始订阅
 * 5. 启动 Spring Boot HTTP 服务（端口 8085）
 */
@SpringBootApplication
public class StatsReporterApp {

    public static void main(String[] args) {
        SpringApplication.run(StatsReporterApp.class, args);
        System.out.println("[StatsReporter] HTTP 统计服务已启动，端口 8085");
    }

    @Configuration
    static class StatsConfig {

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
        SqlStatsPersistence sqlStatsPersistence(
                @Value("${sqlserver.url}") String url,
                @Value("${sqlserver.username}") String username,
                @Value("${sqlserver.password}") String password) {
            return new SqlStatsPersistence(url, username, password);
        }

        @Bean
        PredictionEngine predictionEngine() {
            return new PredictionEngine();
        }

        @Bean
        StatsCollector statsCollector(
                Blackboard board,
                MessageQueue mq,
                SqlStatsPersistence sqlStatsPersistence,
                PredictionEngine predictionEngine) {
            StatsCollector collector = new StatsCollector(board, mq, sqlStatsPersistence, predictionEngine);
            collector.start();
            return collector;
        }
    }
}
