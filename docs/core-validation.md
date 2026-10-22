# 核心验收实测记录

日期：2026-10-22；Java 17.0.20.1；Maven 3.6.3；本地 H2 文件库。

本阶段执行完整 `mvn package`：66 个测试，0 失败、0 错误、0 跳过，构建成功。P4 阶段的 HTTP 人工验收脚本也在已打包服务上通过。

| 验收点 | 对应测试 | 实际结果 |
| --- | --- | --- |
| 向量确定性、空文本、余弦及 Top-K | `VectorAlgorithmTest`、`VectorProcessDeterminismTest` | 黄金字节、跨进程一致性、零向量和堆/完整排序对照通过 |
| 异步提交、重复 ID、队列积压 | `TaskWorkflowTest` | 受控 worker、并发唯一性、补调度及拒收无孤儿记录通过 |
| 渠道过滤、失效排除、完成不覆盖失效 | `SearchWorkflowTest` | 中文渠道、RUNNING 时失效、完成后标记保留通过 |
| 并发命中计数和多行回滚 | `SearchConcurrencyTest` | 6 个同时成功的 Top-K 请求：前两篇各 +6，第三篇 +0；第二条更新前失败导致整批回滚 |
| 取消/整体超时释放资源 | `SearchResourceReleaseTest` | 取消和超时后写锁可取得，后续检索可再次取得并发许可 |
| H2 文件重启保留业务结果 | `BusinessRestartTest` | 两个独立 JVM：原文、向量字节、命中、失效和任务状态保留；QUEUED/RUNNING 基础恢复通过 |
| 真实进程中断恢复 | `CrashRecoveryTest` | RUNNING 持久化后强制结束进程；新进程完成，attemptCount=2 |
| 数据库故障分类与降级探测 | `DatabaseFailureClassifierTest`、`DatabaseRetryTest`、`DocumentTasksUnknownOutcomeTest`、`TaskEngineDegradeTest` | 已确认回滚才重试；不明提交先核对；不可用时服务保持存活、探测后恢复 |

此记录不把受控故障替身当成所有真实数据库异常的穷举验证。服务器部署、万篇压测、多轮和所有崩溃窗口的强制中断均未做，按用户要求后置。
