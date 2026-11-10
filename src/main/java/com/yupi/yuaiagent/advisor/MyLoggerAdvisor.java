package com.yupi.yuaiagent.advisor;

// Lombok 日志注解：编译时自动生成 log 对象
import lombok.extern.slf4j.Slf4j;
// 流式响应聚合器：把 Flux<ChatClientResponse>（多条数据流）聚合成一条完整响应
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
// ChatClientRequest：封装了发给 AI 的完整请求（Prompt + 上下文）
import org.springframework.ai.chat.client.ChatClientRequest;
// ChatClientResponse：封装了 AI 的完整响应
import org.springframework.ai.chat.client.ChatClientResponse;
// CallAdvisor 接口：同步调用的"顾问"（非流式场景）
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
// CallAdvisorChain：同步调用的"顾问链"（类似 FilterChain，链式传递请求）
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
// StreamAdvisor 接口：流式调用的"顾问"（SSE 流式场景）
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
// StreamAdvisorChain：流式调用的"顾问链"
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
// Flux：响应式数据流（Reactor 框架的核心类型）
import reactor.core.publisher.Flux;

/**
 * MyLoggerAdvisor（自定义日志顾问）—— AI 调用的"行车记录仪"
 *
 * 通俗理解：
 *   如果每一次 AI 调用是一次"开车出行"，
 *   那 MyLoggerAdvisor 就是车上的行车记录仪——不干预驾驶，
 *   但出发前拍一张（请求日志），到达后拍一张（响应日志）。
 *
 * Advisor 是什么？
 *   Advisor 是 Spring AI 提供的一种"拦截器"机制，
 *   可以在 AI 调用前后插入自定义逻辑。
 *   就像 Servlet 的 Filter，或者 Spring 的 Interceptor。
 *
 *   常见的 Advisor 用途：
 *     - MyLoggerAdvisor          → 打日志（本类）
 *     - MessageChatMemoryAdvisor → 注入历史对话
 *     - QuestionAnswerAdvisor    → RAG 检索 + 注入文档
 *     - ReReadingAdvisor         → 改写 Prompt
 *
 * 为什么同时实现 CallAdvisor 和 StreamAdvisor？
 *   - CallAdvisor   → 处理同步调用（.call()），一次性请求-响应
 *   - StreamAdvisor → 处理流式调用（.stream()），AI 逐字生成
 *   本项目的 LoveApp 和 YuManus 都涉及两种调用方式，
 *   所以同时实现两个接口，保证两种场景下都能记录日志。
 *
 * 执行顺序：
 *   请求到达 → before() 打请求日志 → AI 调用 → after() 打响应日志 → 返回
 *
 * getOrder() 返回 0 表示"最先执行"，
 * 多个 Advisor 形成一条链，按 order 从小到大依次执行。
 */
@Slf4j  // ← Lombok：自动生成 log 对象，可以直接用 log.info()
public class MyLoggerAdvisor implements CallAdvisor, StreamAdvisor {  // 同时实现同步和流式两种接口

    // ========================================================================
    // 基础方法：名称 + 优先级
    // ========================================================================

    /**
     * 获取 Advisor 的名字
     *
     * 这个名字会出现在 Spring AI 的日志和调试信息里。
     * this.getClass().getSimpleName() 返回类名，即 "MyLoggerAdvisor"。
     * 比硬编码写死 "MyLoggerAdvisor" 更好——万一类被改名，这里自动跟着变。
     */
    @Override
    public String getName() {
        return this.getClass().getSimpleName();              // = "MyLoggerAdvisor"
    }

    /**
     * 获取执行优先级
     *
     * 多个 Advisor 形成一条"链"，按 order 从小到大依次执行。
     *   order=0  → 最先执行
     *   order=10 → 晚一点执行
     *
     * 本项目只有 MyLoggerAdvisor 一个自定义 Advisor，
     * 所以这个 0 不影响什么。但和其他 Advisor 配合时就要注意顺序了。
     */
    @Override
    public int getOrder() {
        return 0;                                            // 最高优先级，最先执行
    }

    // ========================================================================
    // 钩子方法：请求前 + 响应后
    // ========================================================================

    /**
     * 请求前的钩子：记录发给 AI 的完整 Prompt
     *
     * request.prompt() 包含：
     *   - 系统提示词（AI 的人设："你是 YuManus..."）
     *   - 用户消息（用户说了什么）
     *   - 对话历史（之前聊了什么）
     *   - 工具列表（AI 能调哪些工具）
     *
     * 这个方法不修改请求，只是打个日志，然后把请求原样返回。
     * 如果要做"改写 Prompt"的功能（像 ReReadingAdvisor），
     * 可以在 return 之前修改 request 的内容。
     *
     * private 意味着只有本类内部能调用，外部不可见。
     *
     * @param request 即将发给 AI 的完整请求
     * @return 原样返回请求（不修改）
     */
    private ChatClientRequest before(ChatClientRequest request) {
        log.info("AI Request: {}", request.prompt());        // 打印完整的 Prompt（{} 是占位符）
        return request;                                      // 不修改，原样返回
    }

    /**
     * 响应后的钩子：记录 AI 返回的文本内容
     *
     * chatClientResponse.chatResponse()           → 拿到 ChatResponse 对象
     *   .getResult()                              → 取第一个结果
     *   .getOutput()                              → 取 AssistantMessage
     *   .getText()                                → 取纯文本内容
     *
     * 为什么只打文本，不打完整的 ChatResponse？
     *   完整的 ChatResponse 包含很多元数据（token 使用量、finishReason 等），
     *   对调试来说，AI 说了什么才是最核心的信息。
     *   如果需要看完整内容，改这里的代码就行。
     *
     * private 意味着只有本类内部能调用。
     *
     * @param chatClientResponse AI 的完整响应
     */
    private void observeAfter(ChatClientResponse chatClientResponse) {
        // 从嵌套对象中取出纯文本并打印
        log.info("AI Response: {}",
                chatClientResponse.chatResponse().getResult().getOutput().getText());
    }

    // ========================================================================
    // 同步顾问接口（CallAdvisor）：处理 .call() 方式的调用
    // ========================================================================

    /**
     * 同步调用的拦截方法
     *
     * 调用链：
     *   ChatClient.call()
     *     → MyLoggerAdvisor.adviseCall()          ← 你在这里
     *       → before(request)                     打印请求日志
     *       → chain.nextCall(request)             把请求传给下一个 Advisor（或最终执行 AI 调用）
     *       → observeAfter(response)              打印响应日志
     *     → 返回 response
     *
     * 类比：
     *   就像快递驿站——
     *   包裹进来时扫一下（before），交给下一个环节（chain.nextCall），
     *   包裹出去时再扫一下（observeAfter），但包裹本身不变。
     *
     * @param chatClientRequest 发给 AI 的请求
     * @param chain              Advisor 链（可以调用下一个 Advisor）
     * @return AI 的响应
     */
    @Override
    public ChatClientResponse adviseCall(
            ChatClientRequest chatClientRequest,
            CallAdvisorChain chain
    ) {
        // ① 请求前：打日志
        chatClientRequest = before(chatClientRequest);

        // ② 执行真正的 AI 调用（或传给下一个 Advisor）
        //    chain.nextCall() 会按 order 顺序找到下一个 Advisor 并执行。
        //    如果这是链上最后一个 Advisor，它就直接调 AI。
        ChatClientResponse chatClientResponse = chain.nextCall(chatClientRequest);

        // ③ 响应后：打日志
        observeAfter(chatClientResponse);

        // ④ 返回响应（不修改）
        return chatClientResponse;
    }

    // ========================================================================
    // 流式顾问接口（StreamAdvisor）：处理 .stream() 方式的调用
    // ========================================================================

    /**
     * 流式调用的拦截方法
     *
     * 和 adviseCall() 的区别：
     *   adviseCall()    处理同步调用，返回值是单个 ChatClientResponse
     *   adviseStream()  处理流式调用，返回值是 Flux<ChatClientResponse>（数据流）
     *
     * 为什么流式调用不能像同步一样简单地 after 打日志？
     *   流式调用中，AI 是一点点回复的，没有"一个完整的响应"。
     *   如果每收到一个片段都打一行日志，日志会非常多。
     *   所以用 ChatClientMessageAggregator 把整条流"汇集成一桶水"，
     *   然后在最后统一打一次日志。
     *
     * ChatClientMessageAggregator 做了什么？
     *   它订阅 Flux<ChatClientResponse>，收集所有片段，
     *   拼接成一条完整的 ChatClientResponse，
     *   然后回调 observeAfter() 打日志。
     *
     * @param chatClientRequest 发给 AI 的请求
     * @param chain             Advisor 链
     * @return 流式的 AI 响应（每个片段原样传给下游）
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(
            ChatClientRequest chatClientRequest,
            StreamAdvisorChain chain
    ) {
        // ① 请求前：打日志（和同步一样）
        chatClientRequest = before(chatClientRequest);

        // ② 执行流式 AI 调用（或传给下一个 Advisor）
        Flux<ChatClientResponse> chatClientResponseFlux = chain.nextStream(chatClientRequest);

        // ③ 聚合流式响应，完成后打一次日志
        //    不打印每个片段，而是等全部收齐后打印完整的 AI 回复
        return (new ChatClientMessageAggregator())
                .aggregateChatClientResponse(chatClientResponseFlux, this::observeAfter);
    }
}
