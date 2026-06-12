# Blackboard Multi-Robot Inspection System

基于黑板架构的多机器人协作巡检仿真系统。

## 技术栈

| 组件 | 技术 |
|------|------|
| 编程语言 | Java 17+ |
| 构建工具 | Maven 多模块 |
| 黑板存储 | Redis 5.x+ (Jedis) |
| 消息中间件 | RabbitMQ 3.x+ (amqp-client) |
| JSON 序列化 | fastjson2 |
| 显示层 | WebSocket + HTML5 Canvas |
| 日志 | SLF4J + Logback |

## 项目结构

```
blackboard-system/
├── common/                  # ★ 公共模块（已写完，直接依赖）
│   └── src/main/java/com/blackboard/
│       ├── api/             # 接口定义
│       ├── api/impl/        # 接口实现（Redis + RabbitMQ）
│       ├── constant/        # 常量
│       ├── model/           # 数据模型
│       └── util/            # 工具类（SimpleBridge 供人4使用）
├── controller/              # 人1: Controller 调度器
├── car/                     # 人1: 小车知识源
├── task-configurator/       # 人1: 地图初始化
├── exploration-logger/      # 人1: 探索日志
├── registry/                # 人1: 注册中心
├── navigator/               # 人2: 路径规划
├── target-planner/          # 人2: 目标分配
├── stats-reporter/          # 人3: 统计报告
├── replay-logger/           # 人3: 路径回放
├── display/                 # 人4: 前端桥接 + Web页面
└── launcher/                # 一键启动（联调阶段使用）
```

## 分工

### 人1: Controller + Car + TaskConfigurator + ExplorationLogger + Registry

| 模块 | 功能 |
|------|------|
| controller | 节拍循环调度所有知识源（不算工作量） |
| car | 小车状态机（移动/视野/障碍检测） |
| task-configurator | 初始化地图、障碍物、小车位置、MQ队列 |
| exploration-logger | 每节拍记录探索日志 |
| registry | 知识源注册与发现、心跳检测 |

### 人2: Navigator + TargetPlanner

| 模块 | 功能 |
|------|------|
| navigator | BFS/A* 路径规划 |
| target-planner | 贪心目标分配 |

### 人3: StatsReporter + ReplayLogger

| 模块 | 功能 |
|------|------|
| stats-reporter | 探索率/步数/运行时间统计 |
| replay-logger | 每节拍存快照、提供回放数据 |

### 人4: WebSocketBridge + CommandReceiver + ObstacleManager + UserManager + Web前端

| 模块 | 功能 |
|------|------|
| display | 后端桥接 + 前端页面 |
| — | WebSocketBridge: 黑板→WebSocket推送 |
| — | CommandReceiver: 前端命令→Controller |
| — | ObstacleManager: 障碍物手动/随机设置 |
| — | UserManager: 用户登录/偏好 |
| — | Web前端: HTML5 Canvas 地图渲染 |

## 开发前准备

### 1. 安装 Redis

```bash
# Docker 方式
docker run -d --name redis -p 6379:6379 redis:7

# 或直接下载安装
redis-server.exe
```

验证：`redis-cli ping` → 返回 `PONG`

### 2. 安装 RabbitMQ

```bash
# Docker 方式
docker run -d --name rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:3-management

# 或直接下载安装
```

验证：浏览器打开 `http://localhost:15672`（默认账号 guest/guest）

### 3. 导入项目

```bash
# IDE 中直接打开 blackboard-system/ 目录
# IntelliJ IDEA: File → Open → 选择 blackboard-system/pom.xml
# 等待 Maven 自动下载依赖
```

## 接口说明

所有人依赖 `common` 模块中的接口进行开发：

- `com.blackboard.api.Blackboard` — 黑板操作（Redis CRUD）
- `com.blackboard.api.MessageQueue` — 消息队列操作（RabbitMQ）
- `com.blackboard.api.MessageListener` — 消息回调
- `com.blackboard.util.SimpleBridge` — 人4专用简化工具

各人代码中只需要依赖这些接口，不需要关心具体实现。

## 消息格式

所有 MQ 消息统一 JSON 格式：

```json
{
  "cmd": "TICK_MOVE",
  "data": { "carId": "Car001" },
  "timestamp": 1717234567890
}
```

详见 `com.blackboard.constant.MQKeys` 中的常量定义。