# 文本向量化与检索笔试项目

单机、单实例的 Spring Boot 服务：提交文档后异步生成模拟向量；同步检索返回精确 Top-K，并记录最终命中的次数；文档可逻辑失效。项目以 Java 17 为编译目标，使用 Maven、H2 文件库及 Spring Data JPA；任务状态和计数的条件更新使用同一事务内的 JDBC。不调用模型 API，也不依赖 MQ 或专用向量数据库。

## 构建与启动

需要 Java 17、Maven 3.6.3 或以上版本。请从项目根目录运行：

```sh
java -version
mvn -B clean package
java -jar target/vector-search-0.0.1-SNAPSHOT.jar
```

默认仅监听 `127.0.0.1:8080`。默认数据库前缀是 `./data/vector-search`，实际文件为 `data/vector-search.mv.db`；仓库保留空的 `data/` 目录，运行数据被 Git 忽略。重启时在同一工作目录启动即可复用数据。需要换路径或端口时，例如：

```sh
VECTOR_DB_PATH=/tmp/vector-search-demo java -jar target/vector-search-0.0.1-SNAPSHOT.jar --server.port=18080
```

同一文件库只允许一个服务实例。启动时执行幂等的 `schema.sql` 建表，Hibernate 只校验映射；旧库结构不兼容时启动失败，本项目未提供自动迁移。

## 可复制的 HTTP 演示

在另一个终端运行以下命令，先提交，再查询任务直到 `SUCCEEDED`，然后检索、失效并复查。每次使用新 ID，避免上一次演示留下的文档触发 409。

```sh
DEMO_ID="demo-$(date +%s)-$$"
curl -sS -i -X POST http://127.0.0.1:8080/api/documents \
  -H 'Content-Type: application/json' \
  -d "{\"documentId\":\"$DEMO_ID\",\"text\":\"Java 后端\",\"channel\":\"招聘\"}"

curl -sS "http://127.0.0.1:8080/api/documents/$DEMO_ID/task"
curl -sS "http://127.0.0.1:8080/api/documents/$DEMO_ID"

curl -sS -X POST http://127.0.0.1:8080/api/search \
  -H 'Content-Type: application/json' \
  -d '{"text":"Java 后端","topK":3,"channel":"招聘"}'

curl -sS -X PUT "http://127.0.0.1:8080/api/documents/$DEMO_ID/invalidation"
curl -sS -X POST http://127.0.0.1:8080/api/search \
  -H 'Content-Type: application/json' \
  -d '{"text":"Java 后端","topK":3,"channel":"招聘"}'
```

无需手动轮询和判断响应时，可运行 [人工验收脚本](scripts/manual-acceptance.sh)：

```sh
bash scripts/manual-acceptance.sh
# 改用 18080 端口：BASE_URL=http://127.0.0.1:18080 bash scripts/manual-acceptance.sh
```

脚本每次使用唯一中文渠道隔离检索数据，另提交一篇省略渠道的文档并核对其渠道为 `default`。它会检查异步完成、重复 ID 返回 409、空查询零分 Top-K、命中计数与失效排除；成功输出 `PASS`。同一数据库可连续运行，不删除已有数据，也不会给旧文档增加命中次数。每次留下三篇新文档，其中一篇被标记失效。需要 Python 3 和 `curl`。

## 接口与业务规则

| 接口 | 用途 |
| --- | --- |
| `POST /api/documents` | 接收 `documentId`、`text`、可选 `channel`；202 返回任务 ID 和 `Location` |
| `GET /api/tasks/{taskId}` | 查询 `QUEUED`、`RUNNING`、`SUCCEEDED`、`FAILED` 及失败原因 |
| `GET /api/documents/{documentId}` | 查看原文、渠道、状态、时间、失效标记和命中次数 |
| `GET /api/documents/{documentId}/task` | 按文档 ID 查询对应任务 |
| `GET /api/documents` | 分页列表；支持 `channel`、`invalidated`、`vectorReady`、`taskStatus` 过滤 |
| `POST /api/search` | `text`、`topK`（1–100）、可选 `channel`；返回相似度降序结果 |
| `PUT /api/documents/{documentId}/invalidation` | 幂等逻辑失效，保留第一次失效时间 |

`documentId` 唯一且不可覆盖；1–64 个 ASCII 字母、数字、下划线或连字符是本项目约定，不是题目指定格式。省略渠道时入库为 `default`；检索省略渠道时覆盖全部渠道，渠道允许中文。文本 `null` 被拒绝，空字符串合法。空查询得到零向量，仍按文档 ID 升序返回零分 Top-K 并计数。业务参数错误返回 400，重复 ID 返回 409，积压或暂不可用返回 503。完整字段、长度和异常约定见 [需求文档](docs/requirements.md)。

## 实现思路和范围

提交事务同时保存文档和 `QUEUED` 任务；固定 worker 从有界内存队列领取，数据库条件更新决定执行权。队列满或唤醒遗漏时，周期扫描数据库补调度。向量与 `SUCCEEDED` 状态在同一事务发布；启动时按最多三次执行的上限恢复未完成任务。算法失败写入安全原因，不无限重试。worker 若在非停机状态下意外退出，服务停止接收新业务；重启后按持久化任务状态恢复，不在运行中自动重试。

`charhash-v1` 把 Unicode 单字符与相邻字符对哈希到 256 个桶，L2 归一化后保存为 1,024 字节大端浮点数组。查询使用同一算法；余弦相似度遇到零向量定义为 0。检索按 ID 分批扫描合法候选，用容量 K 的堆得到精确排序；只对最终结果在一个事务中原子递增命中次数。单实例公平读写锁覆盖候选扫描和计数提交；失效及向量发布获取写锁。它易于解释，但检索期间会阻塞写入，且全量扫描不适合大库。

服务只适合单实例、小型演示数据集。模拟向量衡量字符重叠，不是真实语义模型；没有分布式部署、共享缓存、物理删除、自动迁移或部署脚本。配置、事务边界、异常分类和已知限制见 [设计文档](docs/design.md)。

## 验证与交付记录

[核心验收实测记录](docs/core-validation.md)列出确定性算法、队列、过滤、失效、并发计数、回滚、取消资源释放、独立进程重启和一次真实进程中断恢复的测试。[干净目录交付复现记录](docs/delivery-verification.md)包含完整构建、默认路径启动、HTTP 演示和重启复查，以及最终 Java 17 构建与同库两次脚本验收。当前 `mvn -B clean package` 共运行 67 个测试；需在支持 Mockito JVM 附加的常规本地环境运行。真实关键 AI 对话节选及事后摘要保存在 [证据索引](docs/evidence/README.md)，两者分开标注，不含伪造截图。

Git 历史按设计、P1、P2、P3、P4、核心 P5、P6 分阶段提交；可用 `git log --oneline` 检查。实施与后置验证范围见 [阶段计划](docs/plan.md)。
