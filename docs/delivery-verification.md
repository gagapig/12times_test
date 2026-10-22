# 干净目录交付复现记录

日期：2026-10-22；Java 17.0.20.1；Maven 3.6.3；macOS 本地环境。

将当前项目复制到新的 `/private/tmp/vector-search-p6-wiZ4tm`，排除 `.git/`、`target/`、日志和运行库文件，确认 `data/.gitkeep` 存在。此目录无既有编译输出或数据库数据。

1. 首次离线执行 `mvn -o clean package`：**未进入测试**，原因是本机 Maven 缓存缺少 `maven-clean-plugin:3.4.1`。这是环境依赖准备问题，没有改动断言或业务规则。
2. 在同一干净目录允许 Maven 获取插件后执行 `mvn clean package`：**BUILD SUCCESS；66 个测试通过，0 失败、0 错误、0 跳过**。
3. 不设置 `VECTOR_DB_PATH`，直接运行打包 JAR（仅把演示端口设为 18081）：启动成功，日志显示连接 `jdbc:h2:file:./data/vector-search`。
4. 在该目录执行 `BASE_URL=http://127.0.0.1:18081 bash scripts/manual-acceptance.sh`：输出 `PASS: async tasks, duplicate ID, Chinese channel, empty query Top-K, hit count, invalidation`。
5. 停止服务，再以同一目录和默认数据库路径启动；查询脚本创建的文档，得到 `vectorReady=true`、`invalidated=true`、`hitCount=1`，证明默认路径重启后仍保留结果。

复现命令见 [README](../README.md)。核心测试覆盖表见 [core-validation.md](core-validation.md)。没有执行服务器部署、万篇压测或穷举崩溃窗口；这些不是此次交付复现的结果。

## 最终审查收尾（2026-10-22）

- worker 意外退出专项测试先复现了 `RUNNING` 卡住且服务仍 READY 的问题（1 个测试失败）；最小修复后，该测试通过。修复只让服务停止接收，任务由人工重启后的既有恢复流程处理，不在运行中自动重试。
- 使用 Zulu JDK 17.0.20.1 执行 `mvn -o -B clean package`：BUILD SUCCESS，67 个测试，0 失败、0 错误、0 跳过。离线运行使用本地已缓存依赖及临时 Maven Central 镜像配置；仓库构建配置未改。
- 以独立 H2 文件目录在 18083 端口启动此 JAR；对同一数据库连续执行两次 `BASE_URL=http://127.0.0.1:18083 bash scripts/manual-acceptance.sh`，两次均输出 `PASS`。随后只读检查第一次创建的三篇文档仍存在，失效状态及命中数保持 1、0、2；第二次没有改动旧文档。
- 以上使用隔离端口和数据库；未代替用户对其验收环境的最终人工确认。
