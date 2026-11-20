# TestFlow 测试说明

本文件回答三个问题：测了什么、如何复现、结果能说明什么。下面的业务测试结果来自已记录的执行日期；文档更新不代表重新运行过业务测试。

## 1. 测试覆盖哪些能力

| 范围 | 核心检查 |
| --- | --- |
| 配置与权限 | Cookie/CSRF、项目隔离、版本冲突、秘密加密、普通用户越权拒绝 |
| 任务与快照 | 同键同请求不重复创建、配置冻结、当前授权复核、原快照重跑 |
| Worker与故障 | 数据库领取、租约、过期写入拒绝、超时/取消、失联UNKNOWN且不重发HTTP |
| 计划 | ORDERED变量隔离与失败停止；INDEPENDENT预算、竞争及结果一致性 |
| 质量统计 | 终态与Outbox同事务、重复消息不重复统计、失败诊断、积压恢复 |
| 所有权治理 | 最后有效Owner、双Owner并发、跨项目治理、交接与禁用回滚、旧会话失效 |
| 单机部署 | Nginx、容器网络、迁移顺序、数据保留、重启及Broker离线 |

判断是否通过时同时检查HTTP、数据库状态和目标侧调用，不仅检查页面提示或状态码。

## 2. 如何执行测试

### 2.1 全量自动化

先运行不连接真实服务的工具检查：

```powershell
./scripts/test/Test-Tooling.ps1
```

它解析所有PowerShell/JavaScript工具，并在带空格的隔离副本中替换Docker、Java、npm和Maven，检查路径、SkipBuild、停止顺序及失败退出。这只能证明工具编排，不代替下面的真实集成和部署测试。

先按[部署手册附录A1](DEPLOYMENT.md#a1-工具与初始化)准备专用开发/测试依赖，并停止本项目API、Worker和Vite，再执行：

```powershell
./scripts/Verify-CI.ps1
```

该入口使用真实MySQL/Redis的`testflow_test`库，运行Maven集成构建、前端锁定安装、类型检查和生产构建。测试会清理专用测试数据，不能替换为开发库或共享业务库。普通Maven verify默认不执行集成测试。

### 2.2 独立进程专项

先完成全量构建。以下工具按能力命名，每次只执行一个，不与其他测试或开发进程共用测试库。

| 命令 | 验证内容 | 环境影响 |
| --- | --- | --- |
| `./scripts/test/Test-Worker.ps1` | schema拒绝、最小权限、心跳、非Web | 仅专用数据库夹具 |
| `./scripts/test/Test-CaseExecution.ps1` | 真实HTTP、幂等、取消、失联与授权撤销 | 会暂停本项目MySQL约8秒并恢复 |
| `./scripts/test/Test-OrderedPlan.ps1` | 有序变量、失败停止、历史与重跑 | 启动自有测试进程，结束后清理 |
| `./scripts/test/Test-IndependentPlan.ps1` | 独立并发、竞争和结果一致性 | 同上 |
| `./scripts/test/Test-QualityProjection.ps1` | Kafka重复消息、失败与恢复 | 需Kafka，会停止/恢复本项目Broker |
| `./scripts/test/Test-Ownership.ps1` | 真实会话下的治理与禁用 | 新建合成账号和项目 |
| `./scripts/test/Test-LinuxBuild.ps1` | 本机复现Linux构建 | 需要Docker及测试依赖 |

脚本使用18080/18088等测试端口。不要对共享或生产实例运行故障注入，不能用删除卷、清空幂等记录或修改历史迁移处理失败。

所有权浏览器验证先按[部署附录A5](DEPLOYMENT.md#a5-所有权浏览器专项的独立环境)准备测试API18080、新夹具、正确代理的Vite和命名浏览器会话，再通过`./scripts/test/Test-OwnershipBrowser.ps1 -Phase`依次选择viewer、governance、disable、self、responsive；每次传一个值。完整重跑必须准备新夹具，不能操作真实账号凑测试数据。

### 2.3 完整部署与页面

按[部署手册第1节](DEPLOYMENT.md#1-首次运行或代码更新)准备带演示目标的完整部署。以下工具额外需要本机Node24。

| 命令 | 用途 | 是否改变业务数据或中断服务 |
| --- | --- | --- |
| `node scripts/test/deployment-smoke.mjs` | 登录、入口边界、CASE、两种计划、幂等、重跑和报告 | 新建合成项目及运行，不覆盖已有项目 |
| `node scripts/benchmark/deployment-load.mjs performance` | 真实HTTP延迟与端到端运行测量 | 新增合成记录，有请求负载 |
| `node scripts/benchmark/deployment-load.mjs soak` | 低并发短时连续观察 | 新增合成记录，默认1800秒 |
| `node scripts/test/deployment-recovery.mjs` | Kafka积压、Worker失联及整栈重启 | **会中断Kafka/API/Worker及整栈** |
| `node scripts/test/deployment-host-network.mjs` | 容器Worker访问Windows宿主机，并拒绝平台控制端口 | 先按部署附录A6运行合成目标；创建合成记录 |

页面验收检查：配置保存后刷新仍存在；报告区分失败/错误/未知；质量趋势能下钻原报告；普通用户权限受限；禁用影响列出真实项目，缺少继任者禁止提交，取消不发送写请求。

### 2.4 测量与观察工具

| 工具 | 前提与输出 | 影响 |
| --- | --- | --- |
| `scripts/benchmark/Measure-Sql.ps1 -Phase Before/After` | 先Before后After，每次一个值；输出.local/benchmarks | 仅在固定隔离benchmark库写合成元数据及调整索引 |
| `scripts/benchmark/Measure-OutboxCapacity.ps1` | 先准备SQL测量的合成库；输出.local/benchmarks | 对照积压领取，不真正发布事件 |
| `scripts/benchmark/Measure-Resources.ps1` | 完整部署已运行；输出资源JSONL | 只采集，不制造压测流量 |
| `scripts/deployment/Collect-DeploymentEvidence.ps1` | 完整部署已运行；输出.local/verification/deployment | 只读容器状态、数据库容量和一致性 |

Java报告仍在各模块target中，浏览器截图在output/playwright；其他新验证结果按能力进入.local/verification，性能材料进入.local/benchmarks。已有历史文件保持原位置。以上原始材料均排除Git，公开结论必须另行脱敏并说明测试日期与环境。

## 3. 最近已有验证结果

以下功能检查于2026-11-20在工具目录调整后实际执行。没有重新进行性能压测或长期稳定性测试。

| 环境与范围 | 实际结果 |
| --- | --- |
| Windows，真实MySQL/Redis | 全量Java142项：Core43、API单元6、API集成87、Worker6；失败/错误/跳过均0 |
| Linux容器构建，连接专用MySQL/Redis | 完整复核142项通过，失败/错误/跳过均0；首次运行的错误见下文 |
| 前端 | npm锁定安装、TypeScript检查及生产构建通过；JS主包1084.88kB、gzip346.18kB，保留大chunk提示 |
| 工具隔离检查 | 84项通过；覆盖语法、跨工作目录、带空格路径、构建/SkipBuild、停止顺序及失败退出 |
| 六组独立进程专项 | Worker5、单用例12、有序计划10、独立计划10、质量11、所有权9，共57项通过 |
| 所有权浏览器专项 | 20项通过；包含Viewer管理员、影响加载失败、真实409、选择保留、多项目交接和自身操作 |
| 完整Docker部署 | 镜像构建、启动和10项功能冒烟通过；7组Kafka/Worker/容器恢复检查通过 |
| 宿主机网络 | 容器Worker可访问明确授权的host.docker.internal:18089；平台入口18081被执行器拒绝 |
| 部署浏览器复核 | 登录、报告、质量、治理详情及只读边界共5项通过；管理员写请求0、pageerror0 |
| 部署只读核对 | 活动运行、终态缺Outbox、终态缺质量事实均为0；已有test1/project1状态与成员关系未变化 |

首次Linux构建在`QualityProjectionIntegrationIT.parkedRecordsAreIdempotentSanitizedAndCanReplayTrustedSources`出现一次`INVALID_QUALITY_EVENT`错误，其余集成测试通过，Worker模块未执行。保留该失败日志后，仅为测试辅助方法补充被拒绝的合成事件诊断，未修改业务事件校验、测试断言或跳过范围。随后Linux完整构建142项及Windows完整构建142项均通过。首次错误未再次出现，根因尚未确认；复核成功不能证明该问题已消除。

这些结果是对应日期和源码范围的记录，不保证任意新机器首次运行都成功。公开仓库已启用[GitHub Actions](https://github.com/jiuwanl/testflow/actions/workflows/ci.yml)，托管端是否通过以对应提交的实际运行结果为准，不将本机通过结果当作托管结果。

## 4. 性能与稳定性：测量方法和边界

### 4.1 环境与HTTP结果

测量日期为2026-11-19。Windows11、i5-13500H、16逻辑CPU、约15.73GiB内存；Docker Linux引擎7.62GiB。API heap384MiB/Hikari8，Worker heap256MiB/Hikari3、2运行槽/4HTTP，MySQL/Redis/Kafka均为单节点；IDEA和浏览器同时运行。

使用真实Nginx/API请求和Worker网络执行，目标及业务数据为合成场景。每种读取接口先10次预热，再按并发1/4/8/16各200请求，共2400请求；计时包含响应JSON读取，P95/P99采用nearest-rank，失败样本不删除。下表仅列并发16这一档：

| 接口 | 并发 | 成功/请求 | 成功率 | 平均ms | P95ms | P99ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 历史查询 | 16 | 200/200 | 100% | 13.584 | 19.753 | 24.141 |
| 报告查询 | 16 | 200/200 | 100% | 17.136 | 27.440 | 30.022 |
| 质量查询 | 16 | 200/200 | 100% | 17.774 | 24.358 | 27.814 |

测试项目只有少量真实运行，不代表大历史库API容量。另有40次真实单用例，并发提交/等待4，成功40/40，端到端平均2042.925ms、P95 2194.513ms、P99 2222.869ms；它包含排队与轮询，不是目标接口响应时间。

### 4.2 SQL对照

在隔离benchmark库生成50000条历史元数据，预热1次后测20次EXPLAIN ANALYZE。时间只计SQL，不含Docker命令启动；查询与索引一起对照。

| 查询 | 优化前/后平均ms | 优化前/后P95ms | 优化前/后P99ms |
| --- | ---: | ---: | ---: |
| 完成窗口 | 43.205 / 0.195 | 46.200 / 0.283 | 69.600 / 0.285 |
| 深分页 | 49.585 / 7.153 | 53.300 / 8.480 | 68.600 / 10.400 |
| Outbox领取 | 0.097 / 0.032 | 0.134 / 0.046 | 0.220 / 0.121 |

复现入口为`Measure-Sql.ps1 -Phase Before/After`及`Measure-OutboxCapacity.ps1`，每次传一个Phase。工具仅操作固定benchmark库。生成5万条元数据不代表执行5万次HTTP；深OFFSET仍扫描40020个索引项，不是常数时间。

### 4.3 稳定性与资源

候选镜像曾观察1801.034秒、358/358循环通过；随后发现并修复消费者启动问题，因此不能将这30分钟当作修复后镜像的证明。当时修复后的镜像又独立观察602.461秒、120/120循环通过，这是低并发短时观察。

未做24小时以上长测。现有soak工具只支持60～7200秒；资源采样横跨构建、重启和故障，不能用于证明没有内存泄漏。Windows空闲内存曾降至约251MiB，建议只运行一套环境。

## 5. CI和未完成验证

[CI配置](../.github/workflows/ci.yml)已定义全量构建、前端、部署冒烟和恢复检查，限定上传XML和JSON，不上传私密配置或应用日志。推送后在[实际运行列表](https://github.com/jiuwanl/testflow/actions/workflows/ci.yml)选择对应提交，检查任务、步骤和报告；排队、运行中或取消均不代表通过。

一次额外无缓存构建在约573秒后因4个依赖JAR截断失败，Core43项已通过但尚未进入API集成。该失败与成功构建记录分别保留，不能用缓存构建成功代替冷下载成功。

| 尚未完成或当前限制 | 能否作出对应承诺 |
| --- | --- |
| GitHub托管CI逐次验证 | 每次变更以Actions中对应提交的完整结果为准；本机通过不替代托管验收 |
| Linux首次质量测试的偶发错误 | 已保留失败并通过完整复核，根因未确认，不能声称已根治 |
| 24小时长测、生产饱和负载、多节点配额 | 不能宣传生产容量或长期SLA |
| HTTPS、公网安全部署、可信多代理链 | 当前只适合本机回环HTTP；代理后登录限速共享IP预算 |
| 独立备份恢复、磁盘损坏/断电测试、自动清理 | 卷持久化不等于备份或零数据损失 |
| 旧JDK补丁、大chunk、Windows keepalive/Mockito提示 | 保留维护事项，不隐藏失败或跳过校验 |

完整安全审计和生产高可用不在已有测试范围。取消和故障不能撤回目标系统副作用，UNKNOWN不能改写为通过或“肯定没执行”。
