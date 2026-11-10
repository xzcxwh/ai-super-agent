package com.yupi.yuaiagent.agent;

// 自定义日志顾问：每次 AI 调用前后自动打印请求和响应日志
import com.yupi.yuaiagent.advisor.MyLoggerAdvisor;
// Spring AI 的聊天客户端：用来和大模型对话的统一入口
import org.springframework.ai.chat.client.ChatClient;
// Spring AI 的大模型接口（本项目用的是通义千问）
import org.springframework.ai.chat.model.ChatModel;
// 工具回调对象：每个 @Tool 方法被包装成这个，代表一个可被 AI 调用的工具
import org.springframework.ai.tool.ToolCallback;
// @Component：告诉 Spring 这个类归它管（虽然本项目主要用 new 方式创建）
import org.springframework.stereotype.Component;

/**
 * YuManus（鱼皮的 AI 超级智能体）—— 整个继承链的"终点站"
 *
 * 通俗理解：
 *   把整个 Agent 框架比作一辆赛车：
 *     BaseAgent       = 底盘（状态机、循环）
 *     ReActAgent      = 变速箱（Think → Act 模式）
 *     ToolCallAgent   = 发动机（调 AI + 执行工具）
 *     YuManus         = 方向盘 + 油门（给它什么 Prompt、什么工具、怎么跑）
 *
 *   YuManus 自己不写任何逻辑代码！
 *   它只是在构造函数里"装配参数"——告诉父类们：
 *     - 我是谁（systemPrompt）
 *     - 怎么选工具（nextStepPrompt）
 *     - 最多跑多少步（maxSteps = 20）
 *     - 用哪个大模型（ChatClient）
 *
 * 为什么这个类这么短？
 *   这正是"面向对象继承"的精髓——父类已经把通用的逻辑写好了，
 *   子类只需要填上自己特有的配置，不需要重复写代码。
 *
 *   如果以后想做一个"代码助手 Agent"或"客服 Agent"，
 *   只需要像 YuManus 一样继承 ToolCallAgent，换一套 Prompt 就行，
 *   所有 Think-Act 逻辑完全复用。
 *
 * @Component 注解说明：
 *   虽然加了这个注解，但目前的代码在 AiController 里是通过
 *   new YuManus(allTools, dashscopeChatModel) 手动创建的，
 *   而非从 Spring 容器注入。这样做的原因是：
 *   每次请求需要独立的 Agent 实例（独立的 messageList 和 state），
 *   不能多用户共享同一个实例，否则会"串话"。
 */
@Component  // ← 声明这是个 Spring 组件（备用，实际主要用 new 创建）
public class YuManus extends ToolCallAgent {  // ← 继承 ToolCallAgent，获得所有 Think-Act 能力

    /**
     * YuManus 构造函数：装配 Agent 的所有参数
     *
     * 调用时机：
     *   每次有用户请求超级智能体时，AiController 会 new 一个 YuManus：
     *     new YuManus(allTools, dashscopeChatModel)
     *
     * 构造函数只做"配置"不做"逻辑"：
     *   它把名字、提示词、步数、大模型客户端全部设好，
     *   然后 runStream("用户消息") 一调，父类的引擎就自动转起来了。
     *
     * 为什么 Prompt 用英文写？
     *   大模型对英文指令的理解和遵循度通常比中文更好。
     *   用英文写 Prompt 能让 AI 更准确地按指令行动（调工具、终止对话等）。
     *   但 AI 回复用户时仍然会按用户的语言来回答（用户说中文，AI 就回中文）。
     *
     * @param allTools           所有可用的工具数组（从 ToolRegistration 注入）
     * @param dashscopeChatModel 大模型客户端（从 Spring 容器注入）
     */
    public YuManus(ToolCallback[] allTools, ChatModel dashscopeChatModel) {

        // --- ① 调父类构造函数，传入工具列表 ---
        // super(allTools) 调用 ToolCallAgent 的构造函数，
        // 它会保存工具列表、创建 ToolCallingManager、配置 ChatOptions。
        super(allTools);

        // --- ② 给 Agent 起个名字 ---
        // 这个名字会出现在日志里，方便调试时区分是哪个 Agent 在工作
        this.setName("yuManus");

        // --- ③ 设置系统提示词（AI 的"人设"） ---
        // """...""" 是 Java 15 引入的"文本块"（Text Block）语法。
        // 好处：
        //   - 可以写多行文字而不用 + 号拼接
        //   - 保留换行和缩进，像写散文一样自然
        //   - 比一堆 + 号拼接的代码易读得多
        //
        // 这段 Prompt 的内容：
        //   "你是 YuManus，一个全能 AI 助手，你的任务是解决用户提出的任何问题。
        //    你有各种工具可以调用，用来高效完成复杂请求。"
        String SYSTEM_PROMPT = """
                You are YuManus, an all-capable AI assistant, aimed at solving any task presented by the user.
                You have various tools at your disposal that you can call upon to efficiently complete complex requests.
                """;
        this.setSystemPrompt(SYSTEM_PROMPT);              // 设置到父类 BaseAgent 的字段里

        // --- ④ 设置下一步提示词（AI 的"行动指南"） ---
        // 这段 Prompt 告诉 AI "怎么选工具"：
        //   1. 根据用户需求，主动选择最合适的工具
        //   2. 复杂任务可以分解，分步骤用不同工具解决
        //   3. 每次用工具后，清楚解释结果并建议下一步
        //   4. 想停止时，用 terminate 工具
        //
        // 这个提示词只在第一轮 think() 时被加入对话上下文（防重复）。
        String NEXT_STEP_PROMPT = """
                Based on user needs, proactively select the most appropriate tool or combination of tools.
                For complex tasks, you can break down the problem and use different tools step by step to solve it.
                After using each tool, clearly explain the execution results and suggest the next steps.
                If you want to stop the interaction at any point, use the `terminate` tool/function call.
                """;
        this.setNextStepPrompt(NEXT_STEP_PROMPT);         // 设置到父类 BaseAgent 的字段里

        // --- ⑤ 设置最大执行步数 ---
        // 默认是 10 步（在 BaseAgent 定义的），这里改成 20 步。
        // 意思是 Agent 最多可以执行 20 轮 Think-Act。
        // 20 步对于复杂任务（比如"搜资料 → 写文件 → 生成 PDF"）够用了，
        // 同时也能防止 AI 陷入死循环消耗太多 API 费用。
        this.setMaxSteps(20);

        // --- ⑥ 创建 ChatClient（AI 聊天客户端） ---
        // ChatClient 是和大模型对话的统一入口，这里用建造者模式配置：
        //   - dashscopeChatModel：用哪个大模型（通义千问）
        //   - MyLoggerAdvisor：每次 AI 调用前后自动打日志
        //
        // 注意：这里没有像 LoveApp 那样配置 MessageChatMemoryAdvisor（对话记忆）。
        // 因为超级智能体的"记忆"是靠 messageList 手动维护的（在 ToolCallAgent 里），
        // 不需要额外的记忆顾问。
        ChatClient chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultAdvisors(new MyLoggerAdvisor())      // 只加了日志顾问
                .build();
        this.setChatClient(chatClient);                  // 设置到父类 BaseAgent 的字段里
    }
}
