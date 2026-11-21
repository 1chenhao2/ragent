# Ragent · Java / Python Agent 运行时改造

这是基于开源 Ragent 的个人改造分支。保留前端现有的 Java 聊天与 SSE 接口，Java 负责鉴权、会话持久化和业务工具校验，Python 负责 Agent 编排和知识库问答。

[运行时架构与配置](docs/architecture/java-python-agent-runtime.md) · [OpenAPI 接口契约](docs/contracts/agent-service-v1.openapi.yaml) · [Python 服务](agent-service/README.md)

## 这个分支实现了什么

- **运行时切换与流式兼容**。通过 `AgentGateway` 接入 Python 服务，前端继续调用 `/rag/v3/chat`。Java 映射 Python SSE 增量和终止事件，负责会话消息落库，并把用户取消转发到对应的 Python Run。[Java 适配器](bootstrap/src/main/java/com/nageoffer/ai/ragent/agent/python/PythonAgentGateway.java)
- **限定知识库范围**。Java 签发短期委托令牌，并通过业务工具返回本次请求可访问的知识库。Python 用 LangGraph 编排，通过 pgvector 检索允许范围内的证据，再生成带来源的回答。[Agent 编排](agent-service/src/ragent_agent/runtime/graph.py) · [业务工具契约](docs/contracts/business-tools-v1.openapi.yaml)
- **运行状态与恢复**。运行记录和 LangGraph 检查点支持独立的 PostgreSQL 持久化。服务重启时标记中断的运行，等待审批的任务可以用新委托令牌恢复。[运行记录](agent-service/src/ragent_agent/runtime/store.py) · [检查点配置](agent-service/src/ragent_agent/runtime/checkpoint.py)
- **验证入口**。[Java SSE 契约测试](bootstrap/src/test/java/com/nageoffer/ai/ragent/agent/python/PythonAgentGatewayContractTest.java) · [Python 检索测试](agent-service/tests/test_rag_retrieval.py) · [问答回归样例](agent-service/evals/README.md)

## 当前进度

默认继续使用 Java 运行时。Python 知识库问答和文本入库需要显式配置，适合在开发环境验证。部署条件、支持的流程和切回方式见[运行时说明](docs/architecture/java-python-agent-runtime.md)。

## 上游与许可

本仓库基于开源 [nageoffer/ragent](https://github.com/nageoffer/ragent)。本分支改写了首页说明，以展示 Java / Python 运行时接入的改造内容。原项目的版权声明和 [Apache License 2.0](LICENSE) 保留在源码中。
