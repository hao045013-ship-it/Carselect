# 回放短期改造方案

## 目标

在不重做数据库结构、不影响实时仿真的前提下，让回放支持完整播放，并尽量避免大地图、长时间运行时卡顿。

短期方案的核心不是减少数据库里的完整数据，而是改变读取方式：

```text
现在：前端一次性读取全部快照 -> 再抽样成 120 帧 -> 播放
改为：后端按 tick 区间读取 -> 前端分段缓存 -> 边播边加载
```

这样数据库仍然保存完整回放，前端也可以完整播放，但浏览器内存和单次请求压力会小很多。

## 当前问题

### 1. 前端一次性拉全量快照

当前前端在 `display/src/main/resources/static/app.js` 中加载历史回放时，请求：

```text
GET /api/replay/sessions/{sessionId}/snapshots
```

这会把该 session 的全部快照一次性返回。每条快照里还有完整 `stateJson`，地图越大、车越多、tick 越多，响应越慢。

### 2. 前端最多只保留 120 帧

当前前端有：

```js
var REPLAY_MAX_FRAMES = 120;
```

加载后会调用 `sampleSnapshots(...)`，把完整快照抽样到最多 120 帧。所以现在的回放更像“快速预览”，不是完整逐 tick 回放。

### 3. 后端已经有区间查询基础

`replay-logger/src/main/java/com/blackboard/replay/ReplayHttpServer.java` 已经支持：

```text
GET /api/replay/sessions/{sessionId}/snapshots?from=0&to=100
```

`SqlReplayPersistence.listSnapshots(sessionId, fromTick, toTick)` 也已经按 tick 范围拼接 SQL。

所以短期改造重点在前端播放逻辑，不需要大改后端。

## 推荐短期方案

### 第一步：前端分段加载

把历史回放分成固定大小的 tick 段，例如每次加载 200 tick：

```js
var REPLAY_CHUNK_SIZE = 200;
var REPLAY_PREFETCH_THRESHOLD = 60;
```

初次选择 session 时，不再请求全部快照，而是请求：

```text
GET /api/replay/sessions/{sessionId}/snapshots?from=0&to=199
```

播放到接近当前缓存尾部时，再预加载下一段：

```text
GET /api/replay/sessions/{sessionId}/snapshots?from=200&to=399
```

### 第二步：前端保留滑动缓存

前端不要永久保存所有已经播放过的帧。可以只保留当前播放点附近的数据：

```text
当前 tick 前 100 帧
当前 tick 后 400 帧
```

已播放很久的旧帧可以从内存里移除。这样完整回放可以一直播放，但页面不会越播越卡。

### 第三步：区分快速预览和完整回放

建议保留两个模式：

```text
快速预览：最多抽样 120 帧，适合快速看结果
完整回放：按 tick 分段加载，完整播放
```

如果暂时不想加 UI 开关，也可以先默认历史 session 使用完整回放，删除或绕开 `sampleSnapshots(...)`。

### 第四步：给 session 增加帧数提示

现在下拉框只有：

```text
4ea624ee · 3车
```

建议后续显示为：

```text
4ea624ee · 3车 · 15x15 · 286帧
```

这样用户能知道哪个 session 大、哪个可能加载慢。

这个不是完整回放的必要条件，但会明显减少误解。

## 需要改的文件

### 前端

主要改：

```text
display/src/main/resources/static/app.js
```

重点函数：

```js
loadReplaySession(sessionId)
sampleSnapshots(snaps, maxFrames)
startReplay()
replaySeek(step)
updateReplayFrame()
```

建议新增函数：

```js
loadReplayChunk(fromTick, toTick)
ensureReplayBuffer()
appendReplaySnapshots(data)
trimReplayBuffer()
```

### 后端

短期可以不改，已有接口基本够用：

```text
replay-logger/src/main/java/com/blackboard/replay/ReplayHttpServer.java
replay-logger/src/main/java/com/blackboard/replay/SqlReplayPersistence.java
```

如果要进一步优化，可以后续补：

```text
GET /api/replay/sessions/{sessionId}/meta
```

返回：

```json
{
  "sessionId": "...",
  "firstTick": 0,
  "lastTick": 286,
  "snapshotCount": 287,
  "carCount": 3,
  "mapWidth": 15,
  "mapHeight": 15
}
```

## 数据库建议

为了让 tick 区间查询更快，建议数据库同学确认 `snapshot` 表有索引：

```sql
CREATE INDEX idx_snapshot_session_tick
ON snapshot(session_id, tick);
```

如果已经有类似索引，就不用重复建。

这个索引很重要，因为完整回放会频繁按：

```sql
WHERE session_id = ? AND tick >= ? AND tick <= ?
ORDER BY tick ASC
```

查询。

## 验收标准

短期改完后，应该满足：

1. 小地图回放仍然能正常播放。
2. 大地图或长 session 不再一次性等待很久才开始。
3. 历史回放可以从第一帧开始逐段播放。
4. 播放过程中前端内存不会明显持续增长。
5. 网络请求可以看到多次 `from/to` 分段请求，而不是一次性请求全部 snapshots。
6. 原来的实时仿真不受影响。

## 风险点

### 1. 倒退播放

如果用户点击上一帧，而那一帧已经被滑动缓存清掉，就需要重新请求对应 tick 区间。

短期可以简单处理：

```text
上一帧还在缓存里：直接回退
上一帧不在缓存里：重新加载对应区间
```

### 2. tick 不连续

如果数据库不是每个 tick 都存一帧，前端不能假设数组下标等于 tick。应该用快照里的 `tick` 字段判断播放位置。

### 3. 当前会话 Redis 回放

`current` 当前会话可以先保留原逻辑，因为 Redis 快照通常用于短期实时查看。短期重点先优化 SQL 历史 session。

## 推荐实施顺序

1. 先改前端历史 session 的加载方式，使用 `from/to` 分段请求。
2. 去掉历史完整回放里的 `REPLAY_MAX_FRAMES = 120` 抽样限制。
3. 加预加载和滑动缓存。
4. 验证 15x15、20x20、更多 tick 的 session。
5. 再考虑 session 列表显示帧数、地图大小、开始时间。

