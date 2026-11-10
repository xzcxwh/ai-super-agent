package com.yupi.yuaiagent.controller;

// ==================== import 区域：引入需要用的类 ====================

// 自己项目里的类：AI 超级智能体
import com.yupi.yuaiagent.agent.CodeAssistant;
// 自己项目里的类：AI 超级智能体（能自主规划、调用工具）
import com.yupi.yuaiagent.agent.YuManus;
// 自己项目里的类：AI 恋爱大师对话能力
import com.yupi.yuaiagent.app.LoveApp;
// Spring 的依赖注入注解：帮我们自动获取 Bean，不用自己 new
import jakarta.annotation.Resource;
// Spring AI 的大模型聊天接口（本项目用的是阿里通义千问 DashScope）
import org.springframework.ai.chat.model.ChatModel;
// Spring AI 的工具回调（每个 @Tool 注解的方法会被封装成 ToolCallback）
import org.springframework.ai.tool.ToolCallback;
// 媒体类型常量，比如 "text/event-stream" 表示 SSE 流式传输
import org.springframework.http.MediaType;
// 用来包装 SSE 事件，可以自定义事件 id、event 类型等
import org.springframework.http.codec.ServerSentEvent;
// 标记一个方法是 GET 请求的接口
import org.springframework.web.bind.annotation.GetMapping;
// 给整个 Controller 的所有接口加一个公共的 URL 前缀
import org.springframework.web.bind.annotation.RequestMapping;
// 标记这个类是一个 REST 控制器（返回 JSON 数据，不走页面渲染）
import org.springframework.web.bind.annotation.RestController;
// SSE（Server-Sent Events）发射器，用于服务端向浏览器推送数据流
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
// Reactor 框架的响应式流：一个能随时间推移发射多条数据的序列
import reactor.core.publisher.Flux;

import java.io.IOException;

/**
 * AI 对话控制器
 *
 * 通俗理解：
 *   这个类就像是饭店的"前台服务员"。
 *   客人（前端页面）点什么菜（调用哪个 URL），服务员就去找对应的厨师（LoveApp、YuManus），
 *   然后把菜（AI 回复）端给客人。
 *
 * URL 规则：
 *   整个类的所有接口都以 /api/ai 开头（因为 application.yml 里配了 context-path=/api，
 *   而 @RequestMapping("/ai") 又加了一层）
 *   所以实际访问地址是：http://localhost:8123/api/ai/具体接口路径
 */
@RestController // ← 告诉 Spring：这个类里的方法返回值直接写成 HTTP 响应，不走模板渲染
@RequestMapping("/ai") // ← 所有接口的 URL 前面都自动拼上 /ai
public class AiController {

    // ==================== 依赖注入（Spring 自动帮我们找"弹药"） ====================

    /**
     * 注入 AI 恋爱大师对话能力
     *
     * 这个 LoveApp 对象是 Spring 在启动时创建好的单例 Bean，
     * 里面封装好了大模型连接、对话记忆、系统提示词等。
     *
     * @Resource 的作用：Spring 会自动从容器里找到 LoveApp 对象，赋值给这个变量。
     * 我们不需要写 loveApp = new LoveApp(...)，Spring 帮我们搞定。
     */
    @Resource
    private LoveApp loveApp;

    /**
     * 注入本项目所有可用的工具列表
     *
     * ToolCallback[] 是一个数组，每个元素都是一个可被 AI 调用的工具。
     * 这些工具在 ToolRegistration.java 里集中注册，包括：
     *   - FileOperationTool   读写文件
     *   - WebSearchTool       百度搜索
     *   - WebScrapingTool     抓网页内容
     *   - ResourceDownloadTool 下载资源
     *   - TerminalOperationTool 执行终端命令
     *   - PDFGenerationTool   生成 PDF
     *   - TerminateTool       终止 Agent
     *
     * AI 大模型本身不会干这些事，但通过"工具调用"机制，
     * AI 可以"说"它想用哪个工具，然后程序帮它执行。
     */
    @Resource
    private ToolCallback[] allTools;

    /**
     * 注入大模型聊天对象
     *
     * 这是连接阿里云百炼 DashScope 的聊天模型客户端。
     * 配置信息来自 application.yml 里的 spring.ai.dashscope 一段。
     *
     * 通过这个对象，可以把消息发给通义千问大模型，拿到 AI 的回复。
     * 它是所有 AI 对话能力的底层依赖。
     */
    @Resource
    private ChatModel dashscopeChatModel;

    // ==================== 接口 1：恋爱大师 - 同步模式 ====================

    /**
     * 🔵 同步调用 AI 恋爱大师（最简单的方式）
     *
     * 完整 URL：GET http://localhost:8123/api/ai/love_app/chat/sync?message=你好&chatId=user001
     *
     * 工作流程：
     *   用户发请求 → 服务器调用 AI → 等待 AI 回答完毕 → 一次性返回全部内容
     *
     * 类比：
     *   像打电话，你说一句话，等对方全部说完后你才能听到完整回复。
     *   好处是简单，坏处是用户要等比较久（尤其是 AI 回复很长时）。
     *
     * @param message 用户输入的消息（Spring 自动从 URL 参数里取）
     * @param chatId  对话 ID，用于区分不同用户/不同会话（传同一个 chatId 可以保持上下文记忆）
     * @return AI 的完整回复文本
     */
    @GetMapping("/love_app/chat/sync")
    public String doChatWithLoveAppSync(String message, String chatId) {
        // 直接把消息转发给 LoveApp，等它返回结果
        return loveApp.doChat(message, chatId);
    }

    /**
     * 🟠 RAG 知识库对话（实验 3：基于你自己的文档回答）
     *
     * 和普通 sync 的区别：AI 会先从知识库检索相关文档，再基于文档内容回答。
     * 完整 URL：GET http://localhost:8123/api/ai/love_app/chat/rag?message=HashMap的底层原理是什么&chatId=rag_test
     */
    @GetMapping("/love_app/chat/rag")
    public String doChatWithLoveAppRag(String message, String chatId) {
        return loveApp.doChatWithRag(message, chatId);
    }

    // ==================== 接口 2：恋爱大师 - 响应式流模式（Flux） ====================

    /**
     * 🟢 SSE 流式调用 AI 恋爱大师（推荐前端使用的模式）
     *
     * 完整 URL：GET http://localhost:8123/api/ai/love_app/chat/sse?message=你好&chatId=user001
     *
     * 注意 produces = MediaType.TEXT_EVENT_STREAM_VALUE：
     *   这告诉浏览器"这个接口返回的是 SSE 事件流，不是普通文本"。
     *   浏览器收到这个标记后，会保持连接不断开，逐条接收数据。
     *
     * 工作流程：
     *   用户发请求 → 服务器开始调用 AI → AI 每生成一个词就推给前端 → ...持续推送直到结束
     *
     * 类比：
     *   像直播，主播每说一个字你就能立刻听到，不用等整段话说完。
     *
     * 返回值 Flux<String>：
     *   Flux 是 Reactor 框架的"响应式流"，可以理解成一个"数据的河流"。
     *   河水（数据）不是一次性涌过来的，而是一滴一滴流过来的。
     *   每滴"水"就是一个 AI 生成的文本片段（通常是一个词或几个字）。
     *
     * @param message 用户输入的消息
     * @param chatId  对话 ID
     * @return 一个持续发射文本片段的响应式流
     */
    @GetMapping(value = "/love_app/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> doChatWithLoveAppSSE(String message, String chatId) {
        // doChatByStream 返回的就是 Flux<String>，直接丢给前端即可
        return loveApp.doChatByStream(message, chatId);
    }

    // ==================== 接口 3：恋爱大师 - ServerSentEvent 包装模式 ====================

    /**
     * 🟡 SSE 流式调用，但每条数据用 ServerSentEvent 包装
     *
     * 和上一个接口的区别：
     *   上一个接口直接返回 Flux<String>，每条数据就是纯文本。
     *   这个接口返回 Flux<ServerSentEvent<String>>，每条数据是一个"事件对象"，
     *   可以附加更多信息（比如事件 id、事件类型、重连时间等）。
     *
     * ServerSentEvent 是 SSE 协议的标准数据结构：
     *   id: 事件编号
     *   event: 事件类型（比如 "message"、"error"、"done"）
     *   data: 实际数据内容
     *
     * 大部分场景用上一个接口（/sse）就够了。这个接口展示了"如果你想自定义事件结构怎么办"。
     *
     * .map(chunk -> ServerSentEvent.<String>builder().data(chunk).build())：
     *   把 Flux 流里的每个文本片段包装成一个 ServerSentEvent 对象
     */
    @GetMapping(value = "/love_app/chat/server_sent_event")
    public Flux<ServerSentEvent<String>> doChatWithLoveAppServerSentEvent(String message, String chatId) {
        return loveApp.doChatByStream(message, chatId)
                .map(chunk -> ServerSentEvent.<String>builder()
                        .data(chunk) // ← 把 AI 输出的片段塞到事件的 data 字段
                        .build());   // ← 构建 ServerSentEvent 对象
    }

    // ==================== 接口 4：恋爱大师 - SseEmitter 模式 ====================

    /**
     * 🔴 SSE 流式调用——手动控制模式（最灵活，但也最复杂）
     *
     * 和上面 Flux 模式的区别：
     *   Flux 是"声明式"的：你把流丢给 Spring，Spring 帮你管理连接。
     *   SseEmitter 是"命令式"的：你自己管理连接的创建、推送、关闭。
     *
     * 什么时候用 SseEmitter？
     *   - 你需要更精细地控制连接生命周期（比如自定义超时、手动断连）
     *   - 你的数据源不是 Reactor Flux，而是其他异步方式（比如线程池）
     *   - 本项目超级智能体用的就是 SseEmitter，因为 Agent 内部是手动 for 循环
     *
     * 逐行解读：
     */
    @GetMapping(value = "/love_app/chat/sse_emitter")
    public SseEmitter doChatWithLoveAppServerSseEmitter(String message, String chatId) {
        // ① 创建一个 SseEmitter，设置超时时间为 180000 毫秒 = 3 分钟
        //    如果 3 分钟内没有数据推送，连接会自动断开
        SseEmitter sseEmitter = new SseEmitter(180000L);

        // ② 拿到 AI 的流式响应（一个 Flux<String>）
        // ③ .subscribe(...) 订阅这个流：流里每来一条数据，就执行对应的回调
        loveApp.doChatByStream(message, chatId)
                .subscribe(
                        // ③-1 第一个回调：流里每来一条数据就调用一次
                        //     把数据通过 SseEmitter 推送给前端
                        chunk -> {
                            try {
                                sseEmitter.send(chunk);  // 把 AI 片段推给浏览器
                            } catch (IOException e) {
                                sseEmitter.completeWithError(e);  // 推送失败则标记错误
                            }
                        },
                        // ③-2 第二个回调：流发生错误时调用
                        sseEmitter::completeWithError,  // 等价于 e -> sseEmitter.completeWithError(e)
                        // ③-3 第三个回调：流正常结束时调用
                        sseEmitter::complete            // 等价于 () -> sseEmitter.complete()
                );

        // ④ 立即返回 SseEmitter 对象给 Spring
        //    注意：此时 AI 还没回复完，但连接已经建立并保持打开
        //    Spring 会持有这个 SseEmitter，等上面的 subscribe 回调慢慢往里推数据
        return sseEmitter;
    }

    // ==================== 接口 5：超级智能体 - ReAct Agent 模式 ====================

    /**
     * 🟣 流式调用 Manus 超级智能体（本项目最核心的接口）
     *
     * 完整 URL：GET http://localhost:8123/api/ai/manus/chat?message=帮我搜一下Spring AI的文档
     *
     * 和上面恋爱大师的本质区别：
     *   恋爱大师：一问一答，AI 只能用"嘴"回答
     *   超级智能体：Think → Act 循环，AI 能"动手"做事
     *     - Think（思考）：AI 判断要不要用工具？用哪个？
     *     - Act（行动）：调工具干实事（搜网页、写文件、生成 PDF...）
     *     - 如果工具的结果不够好，可以再来一轮 Think → Act
     *
     * 为什么要每次 new YuManus？
     *   因为 YuManus 内部有 messageList（对话上下文）和 state（运行状态），
     *   这些是实例级别的数据。如果所有用户共用一个实例，
     *   用户 A 的对话会污染用户 B 的上下文，导致"串话"。
     *   所以每次请求都 new 一个新实例，用完即销毁。
     *
     * YuManus 的构造参数：
     *   - allTools：告诉 Agent "你有哪些工具可以用"
     *   - dashscopeChatModel：告诉 Agent "用哪个大模型来思考"
     *
     * runStream(message) 内部流程：
     *   1. 设置 state = RUNNING
     *   2. 把用户消息放入 messageList
     *   3. for 循环（最多 maxSteps=20 轮）：
     *      a. think()  → 调 AI 判断要不要用工具
     *      b. 如果 AI 说不需要工具 → state=FINISHED，返回 AI 的文字回复
     *      c. 如果需要工具       → act() 执行工具，把结果加入上下文
     *      d. 回到 a，让 AI 看工具结果后继续思考
     *   4. 每一步的结果都通过 SseEmitter 推送给前端
     *
     * @param message 用户输入的消息/任务
     * @return 一个持续推送 Agent 执行步骤的 SseEmitter
     */
    @GetMapping("/manus/chat")
    public SseEmitter doChatWithManus(String message) {
        // ① 每次请求创建全新的 Agent 实例
        YuManus yuManus = new YuManus(allTools, dashscopeChatModel);
        // ② 启动 Agent 的 ReAct 循环，返回 SSE 通道
        return yuManus.runStream(message);
    }

    /**
     * 🟢 流式调用代码助手 Agent（实验 2：你自己的新 Agent）
     *
     * 和 doChatWithManus 的唯一区别：
     *   new CodeAssistant(...) 替代了 new YuManus(...)
     *
     * 这就是继承的威力——换一个子类，整个 Agent 的行为完全变了。
     */
    @GetMapping("/code_assistant/chat")
    public SseEmitter doChatWithCodeAssistant(String message) {
        CodeAssistant codeAssistant = new CodeAssistant(allTools, dashscopeChatModel);
        return codeAssistant.runStream(message);
    }
}
