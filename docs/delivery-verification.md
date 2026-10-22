# 干净目录交付复现记录

日期：2026-10-22；Java 17.0.20.1；Maven 3.6.3；macOS 本地环境。

将当前项目复制到新的 `/private/tmp/vector-search-p6-wiZ4tm`，排除 `.git/`、`target/`、日志和运行库文件，确认 `data/.gitkeep` 存在。此目录无既有编译输出或数据库数据。

1. 首次离线执行 `mvn -o clean package`：**未进入测试**，原因是本机 Maven 缓存缺少 `maven-clean-plugin:3.4.1`。这是环境依赖准备问题，没有改动断言或业务规则。
2. 在同一干净目录允许 Maven 获取插件后执行 `mvn clean package`：**BUILD SUCCESS；66 个测试通过，0 失败、0 错误、0 跳过**。
3. 不设置 `VECTOR_DB_PATH`，直接运行打包 JAR（仅把演示端口设为 18081）：启动成功，日志显示连接 `jdbc:h2:file:./data/vector-search`。
4. 在该目录执行 `BASE_URL=http://127.0.0.1:18081 bash scripts/manual-acceptance.sh`：输出 `PASS: async tasks, duplicate ID, Chinese channel, empty query Top-K, hit count, invalidation`。
5. 停止服务，再以同一目录和默认数据库路径启动；查询脚本创建的文档，得到 `vectorReady=true`、`invalidated=true`、`hitCount=1`，证明默认路径重启后仍保留结果。

复现命令见 [README](../README.md)。核心测试覆盖表见 [core-validation.md](core-validation.md)。没有执行服务器部署、万篇压测或穷举崩溃窗口；这些不是此次交付复现的结果。
