# 文本向量化与检索笔试项目

当前进度：**P3 文档提交与异步向量化**。P1 的工程与持久化、P2 的确定性算法已分别提交。当前服务支持文档提交、任务及文档查询；检索、失效和命中统计仍属 P4。

## 运行前提与命令

- Java 17；Maven 3.6.3 及以上。
- 使用可写的当前目录，或通过 `VECTOR_DB_PATH` 指定一个可写的 H2 文件前缀路径（不带 `.mv.db`）。默认 `./data/vector-search`，相对于启动进程的工作目录。
- 单实例独占该文件库；不要同时启动两个服务指向同一路径。

```sh
mvn test
mvn package
java -jar target/vector-search-0.0.1-SNAPSHOT.jar
```

服务默认监听 `127.0.0.1:8080`。测试结果会在本轮完成后单独报告；此 README 中的命令是复现方法，不表示所有后续业务功能已完成。

## 建表与数据位置

`src/main/resources/schema.sql` 是建表依据。每次启动都会运行幂等建表语句，然后 Hibernate 只做 `validate`；旧表结构不兼容时应启动失败，当前没有自动迁移。数据文件默认位于 `./data/vector-search.mv.db`，已被 `.gitignore` 排除；重新启动必须沿用相同工作目录或指定相同 `VECTOR_DB_PATH`。

P1 的持久化类有文档、任务和四种任务状态映射；测试使用临时文件库与测试专用进程写入样例，生产服务不会自动生成示例业务数据。

## 文档任务接口（P3）

`POST /api/documents` 接收 `documentId`、`text` 和可选 `channel`，成功返回 202、任务 ID 和 `Location`。同一文档 ID 重复提交返回 409；数据库积压达到上限返回 503。`GET /api/tasks/{taskId}` 查询四态及安全失败原因；`GET /api/documents/{documentId}`、`GET /api/documents/{documentId}/task` 查询文档及其任务；`GET /api/documents` 支持分页和渠道、失效、向量就绪、任务状态过滤。提交事务先保存文档与 QUEUED 任务，后台 worker 条件领取，完成时同事务发布向量及 SUCCEEDED。队列满时数据库中的 QUEUED 任务由周期扫描补调度；重启时未完成任务按执行次数上限恢复。默认固定 2 个 worker、有界队列，配置项见 [设计文档](docs/design.md)。

## 模拟向量算法（P2）

`charhash-v1` 以 Unicode 字符及相邻字符对为特征，分别用 1、2 的权重累加到 SHA-256 首字节指定的 256 个桶，随后 L2 归一化为 float。空字符串的 256 项全部为正零。保存为固定 1,024 字节的大端二进制；余弦遇到零向量时定义为 0。Top-K 使用容量 K 的最小堆，分数同分时文档 ID 升序。CPU 工作模拟和向量值分离，同一原文的编码不受模拟轮数影响。该算法衡量字符重叠，不提供真实语义理解。

## 设计与边界

- [需求和接口契约](docs/requirements.md)
- [数据、并发和算法设计](docs/design.md)
- [阶段计划与验收](docs/plan.md)

Git 提交按阶段保存设计、P1、P2、P3；后续阶段尚未开始。
