# 文本向量化与检索笔试项目

当前进度：**P1 工程与持久化**，供设计和基础代码检查。向量化、异步队列、检索、失效及业务 HTTP 接口计划在后续阶段实施；当前启动服务不会执行这些业务。

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

当前 P1 的持久化类有文档、任务和四种任务状态映射；P1 测试使用临时文件库与测试专用进程写入样例，生产服务不会自动生成示例业务数据。

## 设计与边界

- [需求和接口契约](docs/requirements.md)
- [数据、并发和算法设计](docs/design.md)
- [阶段计划与验收](docs/plan.md)

提交记录中第一笔为审核后的设计文档。本轮 P1 代码供用户检查，尚未提交。
