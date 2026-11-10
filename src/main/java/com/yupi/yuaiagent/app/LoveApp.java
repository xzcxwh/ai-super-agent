package com.yupi.yuaiagent.app;

// ==================== import 区域：引入本项目和其他第三方库的类 ====================

// --- 本项目自己的类 ---
import com.yupi.yuaiagent.advisor.MyLoggerAdvisor;        // 自定义日志记录器：每次 AI 调用前后自动打印日志
import com.yupi.yuaiagent.advisor.ReReadingAdvisor;        // 自定义推理增强器（Re2 策略）：让 AI 把问题"再读一遍"提高回答质量
import com.yupi.yuaiagent.chatmemory.FileBasedChatMemory;  // 基于文件的对话记忆：把聊天记录存到磁盘文件里
import com.yupi.yuaiagent.rag.LoveAppRagCustomAdvisorFactory; // RAG 自定义顾问工厂：创建带过滤条件的知识库检索器
import com.yupi.yuaiagent.rag.QueryRewriter;               // 查询重写器：把用户口语化的问题改写成更适合检索的表达

// --- Spring 框架的依赖注入注解 ---
import jakarta.annotation.Resource;                         // 按名称注入 Bean（和 @Autowired 类似，都是让 Spring 帮我们找对象）

// --- Lombok 日志注解 ---
import lombok.extern.slf4j.Slf4j;                          // @Slf4j 注解：编译时自动生成 log 对象，可以直接写 log.info(...)

// --- Spring AI 核心类 ---
import org.springframework.ai.chat.client.ChatClient;      // AI 聊天客户端：整个对话能力的核心入口，像一个大管家
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor; // 对话记忆顾问：自动把历史消息注入到每次请求中
import org.springframework.ai.chat.client.advisor.api.Advisor;             // Advisor 接口：在 AI 调用前后插入额外逻辑（类似拦截器）
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor; // RAG 问答顾问：从向量库检索相关文档，拼到 Prompt 里
import org.springframework.ai.chat.memory.ChatMemory;         // 对话记忆接口
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository; // 基于内存的对话记忆仓库（重启就没了）
import org.springframework.ai.chat.memory.MessageWindowChatMemory;      // 滑动窗口对话记忆（只保留最近 N 条消息）
import org.springframework.ai.chat.model.ChatModel;          // 大模型接口（本项目用的是通义千问 DashScope）
import org.springframework.ai.chat.model.ChatResponse;       // AI 回复对象：包含 AI 生成的文本、工具调用请求等
import org.springframework.ai.tool.ToolCallback;             // 工具回调：每个 @Tool 方法会被包装成这个对象
import org.springframework.ai.tool.ToolCallbackProvider;     // 工具回调提供者（MCP 远程工具的来源）
import org.springframework.ai.vectorstore.VectorStore;       // 向量存储接口：存文档的"数学表示"，用来做相似度搜索
import org.springframework.beans.factory.annotation.Autowired; // 按类型注入 Bean，required=false 表示"没有就算了"
import org.springframework.stereotype.Component;             // @Component 注解：告诉 Spring "这个类交给你管理，帮我创建对象"

// --- 响应式编程 ---
import reactor.core.publisher.Flux;                          // Flux：响应式"数据流"，数据不是一次性返回，而是一点一点流过来

import java.util.List;                                      // Java 标准库的 List


/**
 * LoveApp（AI 恋爱大师）—— 这个项目的第一个 AI 应用
 *
 * 通俗理解：
 *   如果 AiController 是饭店的"前台服务员"，
 *   那 LoveApp 就是后厨的一个"多功能灶台"。
 *
 *   这个灶台能做 5 种菜：
 *     ① doChat()           → 普通炒菜（一问一答）
 *     ② doChatByStream()   → 流水线传菜（边炒边上桌，SSE 流式）
 *     ③ doChatWithReport() → 摆盘上菜（结构化输出，返回固定格式的 JSON）
 *     ④ doChatWithRag()    → 翻菜谱做菜（RAG 知识库，先查资料再回答）
 *     ⑤ doChatWithTools()  → 用机器做菜（Tool Calling，AI 可以调工具）
 *     ⑥ doChatWithMcp()    → 外卖代工（MCP 远程工具调用）
 *
 * 核心依赖：
 *   - ChatClient chatClient：所有 AI 对话的统一入口，在构造函数里创建
 *   - 各种 @Resource / @Autowired 注入的 Bean：向量库、工具、查询重写器等
 *
 * 学习提示：
 *   这个类的每个 public 方法都展示了一种 AI 对话模式。
 *   建议从最简单的 doChat() 开始看，逐个搞懂后面的模式。
 */
@Component // ← "Spring 大哥，这个类归你管，帮我创建好对象放容器里"
@Slf4j     // ← Lombok 注解，编译后自动生成 log 变量，可以直接用 log.info() 打日志
public class LoveApp {

    // ========================================================================
    // 第一部分：核心引擎 —— ChatClient（所有 AI 能力的基础）
    // ========================================================================

    /**
     * ChatClient：AI 对话的统一大管家
     *
     * 这个对象在构造函数里被创建，一旦创建就不会变（所以用 final 修饰）。
     * 后面的所有方法都是在对这个 chatClient 说"帮我发起一次对话"，
     * 只不过每次附加的条件不同（有的加记忆、有的加知识库、有的加工具）。
     *
     * 为什么用 final？
     *   ChatClient 是线程安全的，创建一次，全类复用，不需要变。
     */
    private final ChatClient chatClient;

    /**
     * 系统提示词（System Prompt）：给 AI 的"人设"
     *
     * 这段文字会在每次对话时自动塞到最前面，告诉 AI：
     *   "你是恋爱心理专家，只回答恋爱相关问题，按单身/恋爱/已婚分别引导用户。"
     *
     * 为什么用 static final？
     *   这是一段固定的常量文字，不需要每次改，也不想被意外修改。
     *   static = 类级别共享，final = 不可变。
     *
     * 字符串用 + 号拼接多行：Java 编译器会自动合并成一行连续文本。
     * 也可以换成 Java 15 的文本块 ("""...""") 写法，效果一样。
     */
    private static final String SYSTEM_PROMPT = "扮演深耕恋爱心理领域的专家。开场向用户表明身份，告知用户可倾诉恋爱难题。" +
            "围绕单身、恋爱、已婚三种状态提问：单身状态询问社交圈拓展及追求心仪对象的困扰；" +
            "恋爱状态询问沟通、习惯差异引发的矛盾；已婚状态询问家庭责任与亲属关系处理的问题。" +
            "引导用户详述事情经过、对方反应及自身想法，以便给出专属解决方案。";

    // ========================================================================
    // 第二部分：构造函数 —— LoveApp 出生时要做的事
    // ========================================================================

    /**
     * 构造函数：创建 LoveApp 时自动执行，只执行一次
     *
     * Spring 看到 @Component 注解后，会在启动时：
     *   1. 找到这个构造函数
     *   2. 看到参数需要 ChatModel（大模型），就自动把 dashscopeChatModel 传进来
     *   3. 执行构造函数里的代码，把 chatClient 造好
     *   4. 把创建好的 LoveApp 对象放进容器（后面谁要用就注入给谁）
     *
     * 构造函数里做了两件事：
     *   ① 创建对话记忆（ChatMemory）：让 AI 记住"刚才聊了什么"
     *   ② 创建 ChatClient：把大模型 + 系统提示词 + 记忆 + 日志 组装成一个整体
     *
     * @param dashscopeChatModel Spring 自动注入的大模型对象（通义千问）
     */
    public LoveApp(ChatModel dashscopeChatModel) {
        // === 步骤 1：创建对话记忆（ChatMemory） ===
        //
        // 实验 4：切换到基于文件的持久化记忆
        //   重启项目后 → 去 tmp/chat-memory/ 看有没有 .kryo 文件生成
        //   再发消息问 "我叫什么名字" → AI 记得！

        // 基于文件的记忆（实验 4 启用）
        String fileDir = System.getProperty("user.dir") + "/tmp/chat-memory";
        ChatMemory chatMemory = new FileBasedChatMemory(fileDir);

        // 基于内存的"滑动窗口"记忆（实验 4 注释掉）
        // MessageWindowChatMemory chatMemory = MessageWindowChatMemory.builder()
        //         .chatMemoryRepository(new InMemoryChatMemoryRepository())
        //         .maxMessages(20)
        //         .build();

        // === 步骤 2：创建 ChatClient ===
        chatClient = ChatClient.builder(dashscopeChatModel)                        // 传入大模型，开始建造
                .defaultSystem(SYSTEM_PROMPT)                                      // 设置默认系统提示词（AI 的人设）
                .defaultAdvisors(                                                   // 设置默认的"顾问"列表：
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),      //   ① 记忆顾问：每次请求自动附上历史对话
                        // 自定义日志 Advisor，可按需开启
                        new MyLoggerAdvisor()                                       //   ② 日志顾问：每次请求/响应自动打日志
                        // 自定义推理增强 Advisor，可按需开启
                        // ,new ReReadingAdvisor()                                 //   ③ 推理增强顾问：让 AI 把问题再读一遍（实验性功能）
                )
                .build();                                                           // 建造完成！

        // 现在 chatClient 就是一个"装配好所有插件的 AI 聊天机器人"，
        // 后面的方法只需要调用它，不用再操心记忆、日志这些琐事了。
    }

    // ========================================================================
    // 第三部分：5 种 AI 对话模式（从简单到复杂）
    // ========================================================================

    // -----------------------------------------------------------------------
    // 模式 1：基础对话（同步模式）—— 最简单，适合入门理解
    // -----------------------------------------------------------------------

    /**
     * ① 基础同步对话（支持多轮对话记忆）
     *
     * 这是整个类里最简单的 AI 调用，只做了一件事：
     *   把用户消息发给 AI，等 AI 完全回复后，一次性返回全部内容。
     *
     * 工作流程（逐步拆解）：
     *   ① chatClient.prompt()     → 创建一个新的对话请求
     *   ② .user(message)          → 把用户说的话塞进去
     *   ③ .advisors(spec -> ...)  → 告诉记忆顾问"这次对话的会话 ID 是 chatId"，
     *                                 记忆顾问会根据 chatId 找到之前的聊天记录，自动附上
     *   ④ .call()                 → 真正发起调用，阻塞等待 AI 回复
     *   ⑤ .chatResponse()         → 拿到完整的 ChatResponse 对象
     *   ⑥ .getResult()            → 从响应里取"结果"
     *   ⑦ .getOutput()            → 从结果里取"输出"（一个 AssistantMessage 对象）
     *   ⑧ .getText()              → 从输出里取纯文本内容
     *
     * 链式调用（Builder 模式）：
     *   chatClient.prompt().user().advisors().call().chatResponse().getResult().getOutput().getText()
     *   可以理解成一条流水线：发起请求 → 塞入内容 → 附加配置 → 执行 → 拿结果 → 取文本
     *
     * @param message 用户说的话
     * @param chatId  会话 ID（传不同的 ID 可以区分不同用户/不同话题的对话）
     * @return AI 的完整回复文本
     */
    public String doChat(String message, String chatId) {
        // 发起一次完整的 AI 对话调用
        ChatResponse chatResponse = chatClient
                .prompt()                                                            // ① 新建请求
                .user(message)                                                       // ② 填入用户消息
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))   // ③ 指定会话 ID（让记忆顾问知道这是哪个会话）
                .call()                                                              // ④ 执行调用（同步，会阻塞等结果）
                .chatResponse();                                                     // ⑤ 拿响应
        // 从嵌套的响应对象中提取纯文本内容
        // ChatResponse → Result → Output(AssistantMessage) → Text
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}", content);                                            // 打印日志（{} 是占位符）
        return content;                                                              // 返回给前端
    }

    // -----------------------------------------------------------------------
    // 模式 2：基础对话（流式模式）—— 边生成边推送，用户体验更好
    // -----------------------------------------------------------------------

    /**
     * ② 流式对话（支持多轮对话记忆，SSE 流式传输）
     *
     * 和 doChat() 的唯一区别：返回值类型
     *   doChat()         返回 String         → 等 AI 全部写完，一次性给你
     *   doChatByStream() 返回 Flux<String>   → AI 每写一个字，就推一个字给你
     *
     * Flux<String> 是什么？
     *   想象一条传送带，上面不停有文字滚过来。
     *   它不是"一桶水"（一次倒完），而是"一条河"（持续流过来）。
     *   前端用 SSE（Server-Sent Events）接收，每收到一块就显示在屏幕上。
     *
     * 注意这里用的是 .stream().content() 而不是 .call().chatResponse()
     *   stream() = 流式调用，返回一个 Flux<ChatResponse>
     *   content() = 从 Flux 里提取纯文本，变成 Flux<String>
     *
     * @param message 用户说的话
     * @param chatId  会话 ID
     * @return 一个持续发射文本片段的"数据河流"
     */
    public Flux<String> doChatByStream(String message, String chatId) {
        return chatClient
                .prompt()                                                            // ① 新建请求
                .user(message)                                                       // ② 填入用户消息
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))   // ③ 指定会话 ID
                .stream()                                                            // ④ 流式调用（不是 call！）
                .content();                                                          // ⑤ 提取文本流 → Flux<String>
    }

    // -----------------------------------------------------------------------
    // 模式 3：结构化输出 —— AI 的回复不是随意文字，而是固定格式的"表格"
    // -----------------------------------------------------------------------

    /**
     * LoveReport 记录类：定义 AI 结构化输出的"模板"
     *
     * 这是一个 Java 14+ 的 record（记录类），可以理解成"不可变的纯数据类"。
     * 编译器会自动生成：构造方法、getter、equals、hashCode、toString。
     *
     * 字段说明：
     *   title       → 报告标题
     *   suggestions → 建议列表
     *
     * 和普通 class 的区别：record 一旦创建，字段就不能改了（最适合做"数据传输对象"）。
     */
    record LoveReport(String title, List<String> suggestions) {
        // record 的括号里写完字段，下面不用写任何代码
        // Java 自动帮你搞定构造函数、getter 等一切
    }

    /**
     * ③ AI 恋爱报告功能（结构化输出）
     *
     * 这个模式的核心区别：最后一行不是 .chatResponse().getResult().getOutput().getText()
     *                 而是 .entity(LoveReport.class)
     *
     * .entity(LoveReport.class) 做了什么？
     *   它告诉 Spring AI：
     *     "不要把 AI 回复当成自由文本，而是要求 AI 按 LoveReport 的结构来回答，
     *      然后把 AI 返回的 JSON 自动映射成 LoveReport 对象。"
     *
     * 起作用的原理：
     *   Spring AI 会把 LoveReport 的结构信息（title 是字符串，suggestions 是列表）
     *   自动注入到 Prompt 里，告诉 AI "请按这个格式返回 JSON"。
     *   然后 AI 返回的 JSON 会被自动解析成 LoveReport 对象。
     *
     * 类比：
     *   普通对话：AI 写一篇自由作文
     *   结构化输出：AI 填一张固定格式的表格
     *
     * @param message 用户说的话
     * @param chatId  会话 ID
     * @return LoveReport 对象，包含 title 和 suggestions
     */
    public LoveReport doChatWithReport(String message, String chatId) {
        // 这次在 system prompt 末尾追加了一条指令，要求 AI 输出结构化报告
        LoveReport loveReport = chatClient
                .prompt()
                .system(SYSTEM_PROMPT + "每次对话后都要生成恋爱结果，标题为{用户名}的恋爱报告，内容为建议列表")
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .entity(LoveReport.class);                                           // ← 关键！要求 AI 按 LoveReport 结构返回
        log.info("loveReport: {}", loveReport);
        return loveReport;
    }

    // ========================================================================
    // 第四部分：RAG 知识库相关依赖
    // ========================================================================

    /**
     * 向量存储（基于内存的 SimpleVectorStore）
     *
     * 存的是什么？ 恋爱知识库文档的"数学指纹"（向量）。
     *
     * 简单理解：
     *   "单身怎么追女生"和"单身的社交问题"两句话，
     *   转化成向量后，它们在"数学空间"里的距离很近。
     *   当用户问"怎么追女生"时，系统就能找到"单身的社交问题"这个相关文档。
     *
     * @Autowired(required = false) 是什么意思？
     *   正常情况下 Spring 必须找到这个 Bean，否则启动报错。
     *   加了 required = false 后：找得到就注入，找不到就设成 null，不报错。
     *   为什么这样做？ 因为在 local 开发环境下可能没配向量库，Bean 不一定存在。
     */
    @Autowired(required = false)
    private VectorStore loveAppVectorStore;

    /**
     * 阿里云百炼知识库顾问（云端 RAG）
     *
     * 和上面 loveAppVectorStore 的区别：
     *   loveAppVectorStore → 本地内存向量库（简单，但数据少）
     *   loveAppRagCloudAdvisor → 阿里云云端知识库服务（功能强，但需要额外配置）
     */
    @Autowired(required = false)
    private Advisor loveAppRagCloudAdvisor;

    /**
     * PgVector 向量存储（基于 PostgreSQL 数据库的向量存储）
     *
     * 和 SimpleVectorStore 的区别：
     *   SimpleVectorStore → 存在内存里，重启就没了
     *   PgVector          → 存在 PostgreSQL 数据库里，持久化不丢失
     *
     * 为什么又是 required = false？
     *   因为 PgVector 需要数据库连接，开发环境默认没开。
     */
    @Autowired(required = false)
    private VectorStore pgVectorVectorStore;

    /**
     * 查询重写器：用 AI 改写用户问题
     *
     * 为什么需要改写？
     *   用户问："一直单身怎么办啊好烦"
     *   文档里写的是："单身状态下的心理调适和社交策略"
     *
     *   直接拿用户的问法去检索，可能搜不到文档。
     *   先让 AI 把"一直单身怎么办啊好烦"改写为"单身状态下的心理调适方法"，
     *   再去检索，命中率大幅提升。
     */
    @Resource
    private QueryRewriter queryRewriter;

    /**
     * ④ 和 RAG 知识库进行对话（先查资料，再回答）
     *
     * RAG = Retrieval-Augmented Generation（检索增强生成）
     *
     * 通俗理解：
     *   普通 AI：凭"记忆"回答，可能胡编乱造（幻觉）
     *   RAG AI：先翻"参考书"找到相关内容，然后基于参考书回答
     *
     * 完整流程：
     *   ① 用户问："一直单身怎么办"
     *   ② QueryRewriter 把问题改写为更适合检索的表达
     *   ③ QuestionAnswerAdvisor 从向量库检索相关文档（Top K 相似文档）
     *   ④ 把检索到的文档拼到 Prompt 里："请参考以下资料回答：{文档内容}\n用户问题：{改写后的问题}"
     *   ⑤ AI 基于文档内容生成回答 → 减少了"幻觉"
     *
     * 为什么可以链式调用 .advisors() 多次？
     *   每个 advisor 执行不同的任务，它们按顺序形成一个"处理链"：
     *   MemoryAdvisor → LoggerAdvisor → QuestionAnswerAdvisor → 最终调用 AI
     *
     * @param message 用户问题
     * @param chatId  会话 ID
     * @return AI 基于知识库生成的回答
     */
    public String doChatWithRag(String message, String chatId) {
        // ① 查询重写：把口语化问题改写成"检索友好"的表达
        //    比如 "脱单好难" → "如何有效拓展社交圈以增加遇见合适伴侣的机会"
        String rewrittenMessage = queryRewriter.doQueryRewrite(message);

        ChatResponse chatResponse = chatClient
                .prompt()
                // 用中性系统提示词覆盖默认的"恋爱专家"人设，让 AI 基于文档内容回答
                .system("You are a helpful AI assistant. Answer the user's question based on the provided context documents. If the context doesn't contain the answer, be honest about it. Use the language of the user's question in your response.")
                .user(rewrittenMessage)                                              // ② 使用改写后的查询（而非原始问题）
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))   // ③ 记忆顾问：附上历史对话
                .advisors(new MyLoggerAdvisor())                                     // ④ 日志顾问：打印请求/响应日志
                .advisors(new QuestionAnswerAdvisor(loveAppVectorStore))             // ⑤ RAG 顾问：从向量库检索 + 拼入 Prompt
                // ---- 以下是备选方案（当前被注释掉），展示了不同 RAG 实现 ----
                // .advisors(loveAppRagCloudAdvisor)                                 // 方案 B：阿里云云端知识库
                // .advisors(new QuestionAnswerAdvisor(pgVectorVectorStore))         // 方案 C：PgVector 持久化向量库
                // .advisors(                                                         // 方案 D：自定义顾问（带状态过滤）
                //     LoveAppRagCustomAdvisorFactory.createLoveAppRagCustomAdvisor(
                //         loveAppVectorStore, "单身"                                 // 只检索 status=单身的文档
                //     )
                // )
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }

    // ========================================================================
    // 第五部分：工具调用（Tool Calling）
    // ========================================================================

    /**
     * 本项目所有可用的工具列表
     *
     * 这些工具在 ToolRegistration.java 里集中注册，Spring 自动注入到这里。
     * 包括：文件读写、百度搜索、网页抓取、资源下载、终端命令、PDF 生成、终止工具
     *
     * ToolCallback[] 是一个数组，每个元素代表一个可被 AI 调用的工具。
     */
    @Resource
    private ToolCallback[] allTools;

    /**
     * ⑤ AI 对话（支持调用工具）
     *
     * 普通对话 vs 工具调用对话：
     *   普通：AI 只能"说" → "你可以去搜一下 Spring AI 的文档"
     *   工具：AI 能"做" → AI 调用 WebSearchTool，真的去搜，然后基于搜索结果回答
     *
     * 关键代码：.toolCallbacks(allTools)
     *   这行代码告诉 AI："你有这些工具可以用，想用哪个就告诉我"。
     *   AI 判断需要搜索时，不会自己搜索，而是返回一个"工具调用请求"。
     *   Spring AI 收到请求后，执行对应的 Java 方法（如 searchWeb("Spring AI")），
     *   把结果返回给 AI，AI 再基于结果生成最终回答。
     *
     * 工作流程：
     *   用户: "搜一下 Spring AI 的最新动态"
     *     → AI 思考："这个需要搜索 → 返回 toolCall: searchWeb('Spring AI 最新动态')"
     *     → Spring AI 执行 WebSearchTool.searchWeb(...)
     *     → 搜索结果送回给 AI
     *     → AI: "根据搜索结果，Spring AI 1.0 已于..."
     *
     * @param message 用户说的话
     * @param chatId  会话 ID
     * @return AI 回复（可能经过多轮工具调用后生成的最终答案）
     */
    public String doChatWithTools(String message, String chatId) {
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))   // 记忆顾问
                .advisors(new MyLoggerAdvisor())                                     // 日志顾问
                .toolCallbacks(allTools)                                             // ← 关键！把工具列表交给 AI
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }

    // ========================================================================
    // 第六部分：MCP 远程工具调用
    // ========================================================================

    /**
     * MCP 工具回调提供者
     *
     * MCP = Model Context Protocol（模型上下文协议）
     *
     * 和上面 allTools 的区别：
     *   allTools               → 本地工具（Java 方法，跑在本项目里）
     *   toolCallbackProvider    → 远程工具（另一个独立服务提供的工具，通过 MCP 协议调用）
     *
     * 类比：
     *   本地工具 = 自己厨房里的锅碗瓢盆
     *   MCP 工具 = 叫外卖（工具在别人店里，通过标准化接口远程使用）
     *
     * 本项目配套的 MCP 服务：yu-image-search-mcp-server（图片搜索服务）
     */
    @Autowired(required = false)
    private ToolCallbackProvider toolCallbackProvider;

    /**
     * ⑥ AI 对话（支持调用 MCP 远程工具）
     *
     * 和 doChatWithTools() 的唯一区别：
     *   doChatWithTools()  用 .toolCallbacks(allTools)     → 本地工具
     *   doChatWithMcp()    用 .toolCallbacks(toolCallbackProvider) → MCP 远程工具
     *
     * 其余逻辑完全一样。这也是 MCP 协议的设计理念：
     *   对 AI 来说，它不关心工具是本地的还是远程的，调用的方式都一样。
     *
     * @param message 用户说的话
     * @param chatId  会话 ID
     * @return AI 回复
     */
    public String doChatWithMcp(String message, String chatId) {
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .advisors(new MyLoggerAdvisor())
                .toolCallbacks(toolCallbackProvider)                                 // ← 和 doChatWithTools 的唯一区别：换成 MCP 工具
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }
}
