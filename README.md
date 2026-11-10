# AI 超级智能体项目（ai-super-agent）

> 一个基于 Spring Boot 3 + Spring AI 的全栈 AI 应用项目。
>
> **来源说明**：本项目基于 鱼皮 的《AI 超级智能体》教学项目框架（`yu-ai-agent`）学习与构建，代码在此框架之上，借助 AI 辅助进行理解、调试与二次扩展。感谢原作者提供的优质教学资源。

---

## 项目简介

一套以 **AI 开发实战** 为核心的全栈项目，包含两个核心应用：

1. **AI 恋爱大师（LoveMaster / LoveApp）** —— 面向情感咨询场景的智能对话应用。
2. **AI 超级智能体（SuperAgent / YuManus）** —— 基于 ReAct 模式的自主规划智能体。

技术栈与特性：多轮对话、对话记忆持久化、RAG 知识库问答、Tool Calling 工具调用、MCP 模型上下文协议、SSE 流式输出、向量数据库检索、调用阿里云大模型（DashScope）。

---

## 技术栈

| 层面 | 技术 |
|------|------|
| 后端 | Java 21 + Spring Boot 3 + Spring AI |
| 前端 | Vue 3 + Vite |
| 大模型 | DashScope（阿里云百炼）：`qwen-plus` 对话模型 + 嵌入模型 |
| 向量库 | SimpleVectorStore / PGVector |
| 协议 | SSE 流式输出、MCP（Model Context Protocol） |
| 实用工具 | Kryo 序列化、Jsoup 网页抓取、iText PDF、Knife4j 接口文档 |

---

## 功能特性

### 1. AI 恋爱大师（多轮对话 + RAG + 工具调用）
- **多轮对话**：通过 `ChatClient` + 记忆 Advisor 保持上下文连贯
- **对话记忆持久化**：使用 Kryo 序列化把记忆存到本地文件，重启不丢失
- **RAG 知识库问答**：内置恋爱问答资料（单身 / 恋爱 / 已婚 / 程序员面试指南），用户提问时先检索再回答，支持查询重写提升命中率
- **工具 + MCP 调用**：可调用内置工具与外部 MCP 服务

### 2. AI 超级智能体（ReAct 自主规划）
仿照 OpenManus 思想实现的分层智能体：

```
BaseAgent        ← 状态机 + 执行循环（IDLE→RUNNING→FINISHED）
  └─ ReActAgent  ← 模板方法：step() = think() + act()
       └─ ToolCallAgent ← 用 Spring AI 实现"思考 + 行动"
            └─ YuManus  ← 配置 Prompt 与工具清单
```

让 AI 根据目标**自主推理→调用工具→循环执行→产出成品**，例如搜索约会地点并生成 PDF 计划。

### 3. 工具系统
集中注册了多种供 AI 调用的工具：文件操作、联网搜索、网页抓取、资源下载、终端执行、PDF 生成、终止 Agent。

### 4. MCP 服务
独立子项目 `yu-image-search-mcp-server`，通过 MCP 协议暴露图片搜索能力，支持 Stdio / SSE 双传输模式，主项目可像调用本地工具一样远程调用。

---

## 目录结构（主要）

```
ai-super-agent/
├── src/main/java/com/yupi/yuaiagent/
│   ├── agent/          # 智能体框架（BaseAgent / ReActAgent / ToolCallAgent / YuManus ...）
│   ├── app/            # 恋爱大师应用（LoveApp）
│   ├── rag/            # RAG 知识库（文档加载、向量存储、查询重写、Advisor）
│   ├── tools/          # 工具系统（ToolRegistration + 各 Tool）
│   ├── advisor/        # Advisor 中间件（日志 / 记忆 / RAG / ReReading）
│   ├── chatmemory/     # 对话记忆持久化（Kryo 文件序列化）
│   └── controller/     # REST / SSE 接口入口
├── src/main/resources/document/  # RAG 知识库 markdown 文档
├── yu-ai-agent-frontend/         # Vue 3 前端
└── yu-image-search-mcp-server/   # 独立 MCP 服务（图片搜索）
```

---

## 快速开始

> 前置：Java 21、Maven、Node.js、DashScope API Key（阿里云百炼）

1. **配置密钥**：复制并填写 `src/main/resources/application-local.yml`（该文件已在 `.gitignore`，不会被提交）
2. **启动后端**
   ```bash
   ./mvnw spring-boot:run
   ```
3. **启动前端**
   ```bash
   cd yu-ai-agent-frontend
   npm install
   npm run dev
   ```
4. 浏览器访问前端地址，体验 AI 恋爱大师 / 超级智能体

> 本机快捷测试：替换 `application-local.yml` 中的 `api-key`，运行主程序即可。

---

## 我的扩展与动手记录

在教程框架基础上，我完成了以下二次开发与实验（记录详见项目内笔记）：

- **自定义工具** `GetCurrentTimeTool`：仿照 `FileOperationTool` 新增"获取当前时间"工具并注册
- **自定义智能体** `CodeAssistant`：通过新写 Prompt 实现一个"代码助手"智能体
- **RAG 知识库实验**：新增知识库文档、介绍查询重写与 Top-K 对检索命中率的影响
- **对话记忆持久化**：接入 `FileBasedChatMemory`，验证重启后记忆保留

> 所有实验过程的原理梳理见 `从0到1吃透Agent项目.md` 与 `AI超级智能体面试问答文档.md`。

---

## 面试/学习复盘

- `从0到1吃透Agent项目.md` —— 从 0 到 1 拆解 Agent 项目（分层架构、ReAct 循环、RAG 全链路、动手实验）
- `AI超级智能体面试问答文档.md` —— 覆盖大模型接入、RAG、向量库、Tool Calling、MCP、Agent、Prompt、SSE、记忆、部署 等面试题解

---

## 致谢与版权

本项目主体代码与知识点来自 **鱼皮（程序员鱼皮）**《AI 超级智能体》系列教学项目（[编程导航](https://www.codefather.cn/vip)），遵循原作者的使用说明，仅供个人学习与求职展示使用。若涉及商用或二次分发，请参考原项目（[liyupi/yu-ai-agent](https://github.com/liyupi/yu-ai-agent)）的授权说明。