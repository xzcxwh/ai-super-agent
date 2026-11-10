# 从 0 到 1 吃透 AI Agent 项目

> 项目：yu-ai-agent（Spring Boot 3.4.4 + Vue 3 + Spring AI + DashScope）
> 核心覆盖：Agent 自主规划、RAG 知识检索、Tool Calling、MCP 协议

---

## 项目全景架构

```
┌─────────────────────────────────────────────────────┐
│                    前端 (Vue 3)                       │
│  ChatRoom.vue  LoveMaster.vue  SuperAgent.vue        │
│         │                  │                         │
│         └──── SSE 流 ──────┘                         │
│            /api/ai/love_app/chat/sse                 │
│            /api/ai/manus/chat                        │
└────────────────────┬────────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────────┐
│              AiController.java                       │
│         (所有 API 入口，SSE 流式传输)                  │
└────────┬──────────────────┬─────────────────────────┘
         │                  │
         ▼                  ▼
┌─────────────────┐  ┌──────────────────────────────┐
│    LoveApp      │  │        YuManus               │
│  (AI 恋爱大师)   │  │   (AI 超级智能体/ReAct)       │
│                 │  │                              │
│  chatClient     │  │  ToolCallAgent → ReActAgent  │
│   + RAG         │  │   → BaseAgent                │
│   + Tools       │  │                              │
└────────┬────────┘  └──────────────┬───────────────┘
         │                          │
         ▼                          ▼
┌─────────────────────────────────────────────────────┐
│              Spring AI 层                            │
│  ChatClient  ChatModel  VectorStore  ToolCallback    │
│  Advisor     MCP Client   ChatMemory                 │
└────────────────────┬────────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────────┐
│            DashScope API (阿里云百炼)                  │
│         qwen-plus 模型 / 嵌入模型                      │
└─────────────────────────────────────────────────────┘
```

---

## 第一层：先把骨架跑通（1天）

> 目标：理解"请求进来 → 响应出去"的全链路

### 调试路线

```
前端 ChatRoom.vue → api/index.js → AiController → LoveApp.doChat()
```

### 动手步骤

1. 在 `AiController` 的方法里打断点，跟一遍 `message` 参数怎么进来的
2. 在 `LoveApp.doChat()` 里打断点，看 `chatClient.prompt().user(message).call()` 的返回值结构
3. 在浏览器 F12 → Network 里观察 SSE（Server-Sent Events）流是怎么逐条推送的

### 必读文件

| 文件 | 一句话职责 |
|------|-----------|
| `AiController.java` | 所有 API 入口，路由分发 |
| `LoveApp.java` | 最简 AI 对话实现，理解 ChatClient 怎么用 |
| `application.yml` | 项目所有配置的起点 |
| `yu-ai-agent-frontend/src/api/index.js` | 前端怎么连后端的（SSE 封装） |

### 检查点

- [ ] 能画出"用户发消息 → AI 回消息"经过哪些类
- [ ] 能解释 SSE 和普通 HTTP 请求的区别
- [ ] 知道 `application-local.yml` 为什么在 `.gitignore` 里

---

## 第二层：理解 Agent 的核心——ReAct 模式（2天）

> 这是整个项目最有价值的部分，面试必问
> ReAct = Reasoning（推理）+ Acting（行动），AI 先思考要不要调工具，再决定调哪个

### 继承链

```
BaseAgent           ← 状态机 (IDLE→RUNNING→FINISHED) + 执行循环
  └─ ReActAgent     ← 模板方法: step() = think() + act()
       └─ ToolCallAgent  ← 用 Spring AI 实现 think + act
            └─ YuManus   ← 只配了 Prompt 和工具，不写逻辑
```

### 精读顺序

| 文件 | 重点看什么 |
|------|-----------|
| `BaseAgent.java` | 状态机 4 态流转、`runStream()` 的 for 循环怎么控制步数、`messageList` 怎么维护上下文 |
| `ReActAgent.java` | 模板方法模式：`step()` = `think()` + `act()`，think 返回 false 时怎么取 AI 回复 |
| `ToolCallAgent.java` | **核心文件！** `think()` 怎么调 AI 并解析 toolCalls、`act()` 怎么执行工具并更新上下文、`nextStepPrompt` 为什么只在第一步添加 |
| `YuManus.java` | 上面的框架全是"怎么运行"，这个类才是"运行什么"：System Prompt + Next Step Prompt |
| `AgentState.java` | 状态枚举：IDLE / RUNNING / FINISHED / ERROR |

### 核心流程时序

```
User: "帮我搜一下 Spring AI 的最新文档"

BaseAgent.runStream()
  ├─ state = RUNNING
  ├─ messageList.add(UserMessage)
  │
  ├─ Step 1: think()
  │   ├─ messageList.add(nextStepPrompt)  ← 只在第一次
  │   ├─ ChatClient.prompt(messageList)
  │   │     .system("你是 YuManus...")
  │   │     .toolCallbacks([WebSearch, FileOps, ...])
  │   │     .call()
  │   ├─ AI 返回: toolCalls=[{name:"searchWeb", args:{query:"Spring AI"}}]
  │   └─ return true  ← 需要调工具！
  │
  ├─ Step 1: act()
  │   ├─ toolCallingManager.executeToolCalls()
  │   ├─ WebSearchTool.searchWeb("Spring AI")
  │   ├─ messageList.add(ToolResponseMessage)  ← 工具结果加入上下文
  │   └─ return "工具 searchWeb 返回的结果: {...}"
  │
  ├─ Step 2: think()
  │   ├─ messageList 现在包含: [用户消息, step_prompt, 工具调用, 工具结果]
  │   ├─ ChatClient.prompt(messageList).call()
  │   ├─ AI 看完搜索结果，总结回复
  │   ├─ toolCalls=[]  ← 不需要再调工具了
  │   ├─ state = FINISHED  ← 结束循环
  │   └─ return false
  │
  └─ 发送 AI 的最终回复 → SSE close
```

### 灵魂拷问（回答上来才算懂）

- `think()` 返回 `false` 时，为什么不会像之前那样无限循环了？（答案：设了 `state = FINISHED`，for 循环条件 `state != AgentState.FINISHED` 使其退出）
- `nextStepPrompt` 为什么只在 `messageList.size() <= 1` 时添加？（答案：它是行为指令，重复添加会污染上下文，AI 会迷茫）
- `.toolCallbacks()` 和 `.tools()` 有什么区别？（答案：Spring AI 1.0 API，前者接受 `ToolCallback` 对象，后者需要 `ToolDefinition`）

---

## 第三层：拆解 RAG——知识库对话（2天）

> RAG = Retrieval-Augmented Generation，让 AI 基于你的私有文档回答
> 这是 AI 应用最常用的模式

### 数据流

```
Markdown 文档 (src/main/resources/document/*.md)
  │
  ▼
LoveAppDocumentLoader.loadMarkdowns()
  │  读取 .md 文件，转成 Document 对象列表
  ▼
MyKeywordEnricher.enrichDocuments()
  │  调用 AI 给每篇文档自动生成关键词标签
  ▼
SimpleVectorStore.add(documents)
  │  调用 EmbeddingModel 把文档转向量，存内存
  ▼
VectorStore (loveAppVectorStore)  ← 初始化完成
```

### 检索问答流

```
用户: "单身怎么拓展社交圈？"
  │
  ▼
QueryRewriter.doQueryRewrite()
  │  AI 改写: "单身人士如何有效拓展社交圈子？"
  ▼
QuestionAnswerAdvisor + VectorStore
  │  向量相似度检索 → Top K 相关文档
  │  拼接: "请参考以下资料回答：{文档内容}\n\n用户问题：{改写后的问题}"
  ▼
ChatClient.call()  → AI 基于检索到的文档回答
```

### 精读顺序

| 文件 | 一句话职责 |
|------|-----------|
| `LoveAppVectorStoreConfig.java` | 向量存储初始化：文档→嵌入→存入 |
| `LoveAppDocumentLoader.java` | 加载 markdown 文档 |
| `MyKeywordEnricher.java` | AI 自动给文档打标签 |
| `MyTokenTextSplitter.java` | 大文档切小块（备用） |
| `QueryRewriter.java` | AI 改写用户问题提高检索命中率 |
| `LoveApp.doChatWithRag()` | 上面所有东西串起来的最终调用 |
| `LoveAppContextualQueryAugmenterFactory.java` | 当检索不到内容时的兜底回复 |
| `LoveAppRagCustomAdvisorFactory.java` | 自定义检索过滤（按 status 过滤文档） |

### 动手实验

1. 在 `src/main/resources/document/` 下新增一篇你自己的 markdown
2. 重启后端，问 AI 相关问题，看能否检索到新文档
3. 把 `MyTokenTextSplitter` 的注释取消，对比切分前后检索效果差异
4. 把 `QueryRewriter` 注释掉，对比改写前后检索命中率

### 检查点

- [ ] 能画出"文档入库 → 向量化 → 检索 → 生成回答"全流程
- [ ] 理解 Embedding 是什么、为什么要做（把文字变成可计算距离的向量）
- [ ] 理解 QueryRewrite 的价值（用户口语化问题 ≠ 文档中的书面表达）

---

## 第四层：工具系统——让 AI 动手做事（1.5天）

> Agent 不能只会聊天，要能干实事：搜网页、读写文件、生成 PDF、执行命令...

### 工具注册全景

```
ToolRegistration.allTools()  ← @Bean 集中注册
  │
  ├─ FileOperationTool       读写文件
  ├─ WebSearchTool            百度搜索（需 search-api key）
  ├─ WebScrapingTool          抓网页内容
  ├─ ResourceDownloadTool     下载资源
  ├─ TerminalOperationTool    执行终端命令
  ├─ PDFGenerationTool        生成 PDF
  └─ TerminateTool            终止 Agent（特殊！）
  │
  ▼ 注入到
YuManus.availableTools
  │
  ▼ 传给
ToolCallAgent.think() → ChatClient.toolCallbacks(availableTools)
  │
  ▼ AI 决定调哪个
ToolCallAgent.act() → ToolCallingManager.executeToolCalls()
```

### 精读文件

| 文件 | 重点 |
|------|------|
| `ToolRegistration.java` | 所有工具的集中注册，理解 `@Tool` 注解怎么被发现 |
| `FileOperationTool.java` | **最简工具范本**：`@Tool(description=...)` + `@ToolParam` 两个注解就能让 AI 理解工具用途 |
| `TerminateTool.java` | 特殊工具——让 Agent 知道"我该停了"，`act()` 里通过 `response.name().equals("doTerminate")` 判断 |
| `ToolCallAgent.act()` | 完整执行流程：调工具 → 更新上下文 → 检查终止标记 |
| `WebSearchTool.java` | 带外部 API 调用的工具实战 |

### 动手：写一个新工具

仿照 `FileOperationTool`，写一个 **"获取当前时间"** 的工具：

```java
// 1. 创建 GetCurrentTimeTool.java
public class GetCurrentTimeTool {
    @Tool(description = "Get the current date and time")
    public String getCurrentTime() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}

// 2. 在 ToolRegistration.allTools() 中注册
// 3. 重启，在超级智能体中问 "现在几点了？"
```

### 检查点

- [ ] 理解 `@Tool` 的 `description` 为什么重要（AI 靠它判断该调哪个工具）
- [ ] 理解 TerminateTool 在整个 ReAct 循环里的作用
- [ ] 能自己写出一个新工具并注册

---

## 第五层：扩展视野——Advisor + MCP + 工程细节（1天）

> 理解更高级的扩展点和工程实践

### Advisor 机制（Spring AI 的 AOP）

| 文件 | 职责 | 执行时机 |
|------|------|---------|
| `MyLoggerAdvisor.java` | 打印每次 AI 请求/响应的日志 | before + after |
| `MessageChatMemoryAdvisor` | 自动注入历史对话记忆 | before |
| `QuestionAnswerAdvisor` | RAG 检索 + 上下文注入 | before |
| `ReReadingAdvisor.java` | 改写 Prompt 增强推理（Re2） | before |

工作方式：`ChatClient.builder().defaultAdvisors(advisor1, advisor2, ...)`  

Advisor 像中间件，按 `getOrder()` 排序后链式调用：

```
请求 → Advisor1.before() → Advisor2.before() → AI Call → Advisor2.after() → Advisor1.after() → 响应
```

### MCP（Model Context Protocol）

```
yu-image-search-mcp-server/  (独立子项目)
  │
  └─ ImageSearchTool        以 MCP 协议暴露的图片搜索工具
       │
       ▼ (通过 SSE 或 stdio 连接)
主项目 ← ToolCallbackProvider  像调用本地工具一样调用远程工具
```

核心价值：**工具不再和 Agent 耦合**，MCP server 可以独立部署、独立开发。

### 工程细节速览

| 文件 | 学什么 |
|------|--------|
| `CorsConfig.java` | 前后端分离必备跨域配置 |
| `FileBasedChatMemory.java` | 对话记忆持久化到本地文件（Kryo 序列化） |
| `application.yml` | Profile 机制（local vs prod）、Spring AI 配置组织方式 |
| `Dockerfile` | Spring Boot Docker 化部署 |
| `vite.config.js` | 前端代理和构建配置 |

### 检查点

- [ ] 能说出 Advisor 和 Interceptor 的区别
- [ ] 理解 MCP 解决了什么问题（工具与 Agent 解耦）
- [ ] 能解释 `application-local.yml` 的配置优先级

---

## 推荐学习节奏

```
第1天  ████ 第一层：跑通链路，画请求流程图
第2-3天 ████ 第二层：吃透 Agent 继承链和 ReAct 循环
第4-5天 ████ 第三层：理解 RAG，改文档验证效果
第6-7天 ████ 第四层：写一个自己的工具并接入
第8天   ████ 第五层：过 Advisor/MCP/工程细节
```

---

## 核心原则

> 每看懂一个类，就用一句话说清楚"它解决了什么问题"

| 类 | 解决了什么问题 |
|----|--------------|
| `BaseAgent` | Agent 怎么跑起来、怎么停下来、怎么控制步数 |
| `ReActAgent` | 把一步拆成"想"和"做"两个阶段 |
| `ToolCallAgent` | "想"就是调 AI 看要不要用工具，"做"就是执行工具 |
| `YuManus` | 给上面的框架注入具体的 Prompt 和工具列表 |
| `LoveAppVectorStoreConfig` | 把本地文档变成可检索的向量 |
| `QueryRewriter` | 用户口语 → 更适合检索的表达 |
| `ToolRegistration` | 所有工具的集中管理 |
| `TerminateTool` | 给 Agent 一个"主动结束"的能力 |
| `MyLoggerAdvisor` | 不侵入代码地记录每次 AI 调用 |

---

## 技能映射（面试用）

| 项目中的实现 | 对应面试点 |
|-------------|-----------|
| ReAct 循环 | AI Agent 架构设计 |
| Tool Calling | Function Calling 机制 |
| RAG 全流程 | 检索增强生成 |
| SSE 流式输出 | 服务端推送 / 实时通信 |
| ChatMemory | 对话上下文管理 |
| MCP Client | 模型上下文协议 |
| @Tool 注解 | Spring AI 工具生态 |
| Profile 配置分离 | 工程化配置管理 |

---

> 项目里没有多余的类，每个都有明确职责。关键是理解它们的**协作关系**，而不是孤立地看代码。

---

## 第六层：从"看懂"到"会改"——动手实验

你已经读完了所有核心代码，接下来用 **4 个动手实验** 把知识焊进脑子里。按难度递增：

### 实验 1：写一个新工具（30 分钟）

做一个 **"获取当前时间"** 的工具，让 AI 能回答"现在几点"。

**步骤**：
1. 仿照 `FileOperationTool.java`，新建 `GetCurrentTimeTool.java`
2. 加 `@Tool(description = "Get the current date and time")` 注解
3. 在 `ToolRegistration.allTools()` 里注册
4. 重启 → 在超级智能体问"现在几点了？"

> **验证**：断点打在 ToolCallAgent 第 211 行，看 `toolCallList` 里有没有你的新工具名。

---

### 实验 2：做一个新 Agent（30 分钟）

仿照 `YuManus.java`，新建一个 **"代码助手 Agent"**，只改 Prompt。

**步骤**：
1. 新建 `CodeAssistant.java`，继承 `ToolCallAgent`
2. 把 SYSTEM_PROMPT 改成 `"你是代码助手，专注帮用户写代码和排查 bug"`
3. 在 `AiController` 里加一个新接口 `/code_assistant/chat`
4. 在前端 `api/index.js` 和 Vue 页面加对应的调用

> **验证**：问同样的技术问题，对比 YuManus 和 CodeAssistant 的回答风格差异。

---

### 实验 3：改 RAG 知识库（30 分钟）

让 AI 基于你自己的文档回答。

**步骤**：
1. 在 `src/main/resources/document/` 下新建一篇 markdown，内容是你熟悉的领域知识
2. 重启项目，看控制台有没有 `Calling EmbeddingModel for document id = xxx`
3. 在 `AiController` 里加一个新接口 `/love_app/chat/rag`，调 `loveApp.doChatWithRag(message, chatId)`
4. 发请求验证 AI 能否引用你文档里的内容回答

> **验证**：AI 的回答中包含你文档中的具体信息。

---

### 实验 4：加对话记忆持久化（20 分钟）

让 AI 在重启后还能记得你。

**步骤**：
1. 打开 `LoveApp.java`，取消构造函数里 `FileBasedChatMemory` 那两行的注释
2. 注释掉 `InMemoryChatMemoryRepository` 那行
3. 重启 → 发一条带名字的消息（"我叫小明"）
4. 再重启 → 问"我叫什么名字"

> **验证**：重启后 AI 还能说出你的名字。

---

### 做完 4 个实验之后

如果这些都搞定了，你有两条路：

| 方向 | 做什么 |
|------|------|
| **深挖** | 读 Spring AI 源码，理解 `ChatClient`、`ToolCallingManager` 底层怎么实现的 |
| **实战** | 用学到的 Agent 框架，自己从 0 搭一个新项目（比如"AI 客服 Agent"、"AI 文档助手"） |

---

## 总结：四遍学习法

```
第一遍：读注释，看懂每一行        ✅ 你已完成
第二遍：调试，追踪数据流转         ✅ 你已完成
第三遍：改代码，加功能             ← 你现在在这里
第四遍：删掉重写，独立搭建          ← 最终目标
```
