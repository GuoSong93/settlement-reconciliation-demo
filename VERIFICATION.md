# 验证记录

日期：2026-10-22。所有业务数据、样例 PDF 和本地后台均为本轮全新生成。

| 项目 | 已执行并通过 | 尚未完成的环境验证 |
| --- | --- | --- |
| 结算对账 | JDK 21 编译；业务检查组；Spring MockMvc 接口检查组 | 真实监听端口后的 HTTP 与浏览器交互 |
| 多文件流程 | JDK 21 编译；状态/恢复检查组；Spring MockMvc 接口检查组 | 真实监听端口后的 HTTP 与浏览器交互 |
| PDF 校验 | 10 个 unittest；五类 PDF 生成；正常 CLI 验证；样例渲染检查 | OCR、真实模型适配未实现 |
| 浏览器自动化 | 4 个离线测试方法，覆盖 OTP、PDF 核验、认证及 7 类后台响应 | 2 个浏览器集成测试方法（含 7 个场景）未跑通 |

另已检查全部 Python 文件语法，以及两个 Java 演示页面的 JavaScript 语法。

## Java 检查方法

在现有依赖缓存上用 javac 编译完整 main/test 源码，分别执行 AcceptanceChecks 和 WebAcceptanceChecks。
MockMvc 调用真实 Spring MVC DispatcherServlet 和 Controller，验证请求反序列化、响应 JSON、错误状态和归档下载；不使用网络套接字。
每个项目的 AcceptanceTest 将同样的检查接入 Maven/JUnit，CI 配置执行 mvn verify，但本机没有完成 Maven 标准构建。

- 结算：四类差异、舍入、重复导入、并发确认、跨账单重复分配、失败回滚、事件幂等、三类付款终态、进程锁及重启恢复。
- 流程：条件节点、角色限制、旧版本拒绝、并发提交、动作与任务同时提交、故障重试、遗留 RUNNING 回收、归档文件隔离、历史轮次、模板不可变、死信重试。
- 快照存储是单进程原子文件替换，并非数据库或分布式事务。没有把单机测试结果扩大为生产保证。

## Python 检查方法

PDF：python -m unittest discover -s tests -v，10/10 通过。
浏览器项目：显式设置 SKIP_BROWSER_E2E=1 后，4 个测试通过、2 个浏览器测试标注跳过。
后台离线检查直接执行实际 Handler 路由及响应逻辑，不证明浏览器选择器、页面事件或下载时序已通过端到端验证。

## 当前执行环境限制

本轮沙箱禁止 loopback 连接（WinError 10013），Tomcat NIO 也因此无法启动真实监听服务；Playwright 驱动启动受阻。
初次 Maven 构建受依赖仓库 DNS、缓存写权限及本机 Maven 版本限制，未完成 mvn package/verify。
为避免依赖下载阻塞演示开发，Java 演示使用本地快照存储，并完成直接编译、业务和 MVC 检查。
未声称浏览器端到端已通过、Maven 构建已通过，或 GitHub Actions 已在远端执行。

## 在正常开发环境补验

1. 使用 JDK 21 / Maven 3.6.3+ 进入两个 Java 目录执行 mvn verify，然后按 README 启动并执行 python demo.py。
2. 浏览器项目安装 requirements 和 Chromium，确认未设置 SKIP_BROWSER_E2E，再执行完整 unittest。
3. 运行 browser-automation-lab 的 download-once 与 wrong-amount 场景，检查结果、失败报告与 trace。
4. 亲自核对功能和代码后再发布。本仓库为独立演示项目；远端 CI 执行结果见 Actions 页面。
