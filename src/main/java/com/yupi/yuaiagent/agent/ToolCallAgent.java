package com.yupi.yuaiagent.agent;

// --- Hutool 工具库 ---
// 集合工具类：取列表最后一个元素
import cn.hutool.core.collection.CollUtil;
// 字符串工具类：判断字符串是否为空
import cn.hutool.core.util.StrUtil;
// --- Spring AI Alibaba（阿里云百炼）---
// DashScope 专用的 ChatOptions：可以控制 DashScope 特有的行为
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
// Agent 的 4 种运行状态
import com.yupi.yuaiagent.agent.model.AgentState;
// --- Lombok ---
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
// --- Spring AI 消息类型 ---
// AI 助手消息：AI 的回复（可能是文本，也可能是工具调用请求）
import org.springframework.ai.chat.messages.AssistantMessage;
// 消息基类：对话上下文里每一条数据的抽象
import org.springframework.ai.chat.messages.Message;
// 工具响应消息：工具执行完后返回的结果
import org.springframework.ai.chat.messages.ToolResponseMessage;
// 用户消息：代表"用户说的话"
import org.springframework.ai.chat.messages.UserMessage;
// AI 回复的完整包装对象（包含消息列表、元数据等）
import org.springframework.ai.chat.model.ChatResponse;
// ChatOptions 接口：控制大模型调用时的各种参数
import org.springframework.ai.chat.prompt.ChatOptions;
// Prompt 对象：封装了"发给 AI 的完整请求"（消息列表 + 参数配置）
import org.springframework.ai.chat.prompt.Prompt;
// 工具调用管理器：负责"解析 AI 想调什么工具 → 找到对应 Java 方法 → 执行 → 打包结果"
import org.springframework.ai.model.tool.ToolCallingManager;
// 工具执行结果：包含更新后的对话历史和每个工具的执行结果
import org.springframework.ai.model.tool.ToolExecutionResult;
// 工具回调：每个 @Tool 方法会被 Spring AI 包装成一个 ToolCallback 对象
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
// Stream 流式处理：把工具列表转成格式化的字符串
import java.util.stream.Collectors;

/**
 * ToolCallAgent（工具调用代理）—— ReAct 模式的"发动机"
 *
 * 通俗理解：
 *   如果说 BaseAgent 是跑步机、ReActAgent 是大脑中枢，
 *   那 ToolCallAgent 就是"发动机"——真正干活的东西。
 *
 *   这个类是第一个"可以实例化"的 Agent 类（不再是 abstract），
 *   它用 Spring AI 的 API 具体实现了 think() 和 act()。
 *
 *   你写的 YuManus 就是继承了这个类，所以 YuManus 自动拥有调 AI + 调工具的能力。
 *
 * 这个类的核心职责（两个方法）：
 *   think() = 调 AI → 看 AI 要不要调工具 → 要的话返回 true，不要返回 false
 *   act()   = 执行 AI 要求的工具 → 把结果写入上下文 → 返回执行结果
 *
 * 为什么自己维护 ChatOptions 而不是让 Spring AI 自动处理？
 *   通过 withInternalToolExecutionEnabled(false) 关闭了 Spring AI 的内置工具调用机制。
 *   这样我们可以手动控制整个 Think-Act 循环，每一步做什么完全由我们自己决定。
 *   如果让 Spring AI 自动处理，它会在一次 call() 里自动完成多轮工具调用，
 *   我们就无法在每一步之间插入日志、修改变量、控制流程了。
 */
@EqualsAndHashCode(callSuper = true)  // ← 生成 equals/hashCode 时包含父类的字段
@Data                                  // ← 生成所有字段的 getter/setter
@Slf4j                                 // ← 生成 log 对象
public class ToolCallAgent extends ReActAgent {  // extends ReActAgent：继承了 step() = think()+act() 的模板

    // ========================================================================
    // 第一部分：4 个核心字段
    // ========================================================================

    /**
     * 可用工具列表
     *
     * 这个数组在构造函数里传入，来源是 ToolRegistration.allTools()。
     * 包含了本项目所有可被 AI 调用的工具：
     *   文件读写、百度搜索、网页抓取、资源下载、终端命令、PDF 生成、终止
     *
     * final 表示这个数组引用本身不能变（但数组内容可以），
     * 一个 Agent 实例创建后，它的工具列表就固定了。
     */
    private final ToolCallback[] availableTools;

    /**
     * AI 返回的工具调用响应
     *
     * think() 方法调用大模型后，AI 的回复存在这里。
     * 这个 ChatResponse 里包含了：
     *   - AI 的文字回复（比如"我来帮你搜索"）
     *   - AI 想调用的工具列表（比如 [searchWeb("Spring AI")]）
     *
     * act() 方法会读取这个字段，执行 AI 要求的工具。
     */
    private ChatResponse toolCallChatResponse;

    /**
     * 工具调用管理器
     *
     * ToolCallingManager 是 Spring AI 提供的工具执行引擎。
     * 它能做的事：
     *   1. 解析 ChatResponse 里的 toolCall 列表
     *   2. 找到对应的 ToolCallback（Java 方法）
     *   3. 传入参数，执行方法
     *   4. 把执行结果打包成 ToolExecutionResult
     *
     * 为什么用 final？
     *   管理器是无状态的，创建一次就够了，不需要变。
     */
    private final ToolCallingManager toolCallingManager;

    /**
     * 大模型调用参数配置
     *
     * 通过 DashScopeChatOptions 设置了关键选项：
     *   withInternalToolExecutionEnabled(false)
     *     → 关闭 Spring AI 的"自动多轮工具调用"
     *     → 让我们手动控制每一步 Think-Act
     *
     * 如果开启（默认行为），Spring AI 会在一次 call() 里自动完成：
     *   AI返回toolCall → 执行工具 → 把结果发给AI → AI再返回toolCall → ...
     *   全程黑盒，我们无法干预。
     *
     * 关闭后，每一轮 Think-Act 都由我们的 for 循环显式控制。
     */
    private final ChatOptions chatOptions;

    // ========================================================================
    // 第二部分：构造函数 —— 初始化工具管理器和运行时参数
    // ========================================================================

    /**
     * 构造函数：初始化 ToolCallAgent
     *
     * 调用时机：
     *   不是在 Spring 启动时调用的！ToolCallAgent 没有 @Component 注解。
     *   而是在每次 HTTP 请求到达时，由 AiController 手动 new 出来的：
     *     new YuManus(allTools, dashscopeChatModel)
     *       → super(allTools)  ← 调到这里
     *
     * 构造函数做了两件事：
     *   ① 保存可用工具列表
     *   ② 创建 ToolCallingManager（工具执行引擎）
     *   ③ 配置 ChatOptions（关闭自动工具调用，手动控制每一步）
     *
     * @param availableTools 可被 AI 调用的工具数组
     */
    public ToolCallAgent(ToolCallback[] availableTools) {
        super();                                         // 调父类 ReActAgent 的构造函数
        this.availableTools = availableTools;            // 保存工具列表

        // 创建工具调用管理器（Spring AI 提供的"工具执行引擎"）
        this.toolCallingManager = ToolCallingManager.builder().build();

        // 配置大模型调用选项
        // DashScopeChatOptions 是阿里云百炼专用的选项类
        this.chatOptions = DashScopeChatOptions.builder()
                .withInternalToolExecutionEnabled(false)  // ← 关键！关闭自动工具调用，手动控制每一步
                .build();
    }

    // ========================================================================
    // 第三部分：think() —— "想"：调 AI，看它要不要用工具
    // ========================================================================

    /**
     * 思考阶段：调用 AI 大模型，判断是否需要执行工具
     *
     * 这是整个 Agent 系统中最核心的方法之一。它做了 3 件事：
     *
     *   ① 准备上下文：把对话历史（messageList）打包成 Prompt
     *   ② 发给 AI：调用大模型，告诉它有哪些工具可用，让它决定要不要用
     *   ③ 解析结果：看 AI 返回了什么 → 有 toolCall 就返回 true，没有就 false
     *
     * 返回值含义：
     *   true  → AI 说"我要调这个工具" → 父类 step() 会接着调 act()
     *   false → AI 直接文字回答了   → 父类 step() 取文本返回给用户，循环结束
     *
     * @return true = 需要执行工具，false = 对话完成
     */
    @Override
    public boolean think() {

        // --- ① 只在第一次思考时加入行动指南 ---
        // getNextStepPrompt() 是"下一步提示词"，告诉 AI 怎么选工具。
        //
        // 条件：nextStepPrompt 不为空 且 消息列表里只有用户最初发的那一条消息。
        // 为什么要限制 size() <= 1？
        //   这个提示词是"行为指令"，只需要告诉 AI 一次。
        //   如果每轮 Think 都重复添加，messageList 会变成：
        //     [用户消息, nextStepPrompt, AI回复, nextStepPrompt, 工具结果, nextStepPrompt, ...]
        //   重复的指令会污染上下文，让 AI 困惑。
        if (StrUtil.isNotBlank(getNextStepPrompt()) && getMessageList().size() <= 1) {
            UserMessage userMessage = new UserMessage(getNextStepPrompt());
            getMessageList().add(userMessage);           // 把行动指南加入对话上下文
        }

        // --- ② 打包 Prompt，调用 AI ---
        List<Message> messageList = getMessageList();    // 获取当前完整的对话上下文
        Prompt prompt = new Prompt(messageList, this.chatOptions);  // 打包：消息 + 参数配置

        try {
            // 发起 AI 调用（链式调用，逐步构建请求）：
            //   getChatClient()        → 拿到 ChatClient（YuManus 构造函数里创建的）
            //   .prompt(prompt)        → 把消息上下文和参数传进去
            //   .system(getSystemPrompt()) → 附上系统提示词（AI 的人设）
            //   .toolCallbacks(availableTools) → 告诉 AI "这些工具你可以用"
            //   .call()                → 真正发起 HTTP 请求到阿里云百炼
            //   .chatResponse()        → 拿到 ChatResponse 对象
            ChatResponse chatResponse = getChatClient().prompt(prompt)
                    .system(getSystemPrompt())           // AI 的人设："你是 YuManus..."
                    .toolCallbacks(availableTools)       // 工具列表："你能用这些工具..."
                    .call()                              // 执行调用（同步等待）
                    .chatResponse();                     // 取响应

            // 保存响应，act() 方法需要从这里读取 AI 想调什么工具
            this.toolCallChatResponse = chatResponse;

            // --- ③ 解析 AI 的回复 ---
            // 从 ChatResponse 里取出 AssistantMessage
            // AssistantMessage 是什么？
            //   它是 AI 对你说的话。里面可能包含：
            //     - textContent：AI 的文字回复（"好的，我来帮你搜索..."）
            //     - toolCalls：AI 想调用的工具列表（比如 [{"searchWeb": "Spring AI"}]）
            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();

            // 获取 AI 想调用的工具列表
            // 如果 AI 只是纯文字回复（没想调工具），这个列表就是空的 []
            List<AssistantMessage.ToolCall> toolCallList = assistantMessage.getToolCalls();

            // 打印日志：方便调试，看看 AI 说了什么、想调什么工具
            String result = assistantMessage.getText();
            log.info(getName() + "的思考：" + result);
            log.info(getName() + "选择了 " + toolCallList.size() + " 个工具来使用");
            // 把工具列表格式化成易读的字符串
            String toolCallInfo = toolCallList.stream()
                    .map(toolCall -> String.format("工具名称：%s，参数：%s",
                            toolCall.name(), toolCall.arguments()))
                    .collect(Collectors.joining("\n"));
            log.info(toolCallInfo);

            // --- ④ 无论如何都要记录 AI 的回复到上下文 ---
            // 不管是纯文字回复还是包含 toolCall，AssistantMessage 都要加入 messageList。
            // 这样下一轮 think() 时，AI 能看到"我刚才说了什么"。
            getMessageList().add(assistantMessage);

            // --- ⑤ 判断下一步 ---
            if (toolCallList.isEmpty()) {
                // AI 没有要调用的工具 → 对话完成
                // 设 state = FINISHED，for 循环会在下一次条件判断时退出
                setState(AgentState.FINISHED);
                return false;                            // false = 不需要行动，对话结束
            }

            // AI 想调工具 → 返回 true，让 step() 继续调用 act()
            return true;

        } catch (Exception e) {
            // AI 调用失败（网络断了、API Key 不对、余额不足...）
            // 记录错误日志，写入一条"报错消息"到上下文
            log.error(getName() + "的思考过程遇到了问题：" + e.getMessage());
            getMessageList().add(new AssistantMessage("处理时遇到了错误：" + e.getMessage()));
            setState(AgentState.FINISHED);               // 标记为已完成，停止循环
            return false;                                // 不需要行动
        }
    }

    // ========================================================================
    // 第四部分：act() —— "做"：执行 AI 要求的工具
    // ========================================================================

    /**
     * 行动阶段：执行 AI 在 think() 中要求的工具
     *
     * 调用时机：
     *   只有当 think() 返回 true 时，step() 才会调用 act()。
     *
     * 做了 3 件事：
     *   ① 调用 ToolCallingManager 执行 AI 要求的所有工具
     *   ② 用执行结果更新 messageList（对话上下文）
     *   ③ 检查是否调用了 TerminateTool（终止工具）→ 如果是，标记 FINISHED
     *
     * @return 工具执行结果的文本（格式："工具 XXX 返回的结果：YYY"）
     */
    @Override
    public String act() {

        // 安全检查：如果 AI 没说要用工具，就不该进入 act()
        // 正常流程不会走到这里，是一个防御性编程的兜底
        if (!toolCallChatResponse.hasToolCalls()) {
            return "没有工具需要调用";
        }

        // --- ① 执行工具 ---
        // ToolCallingManager.executeToolCalls() 做了以下事情：
        //   1. 从 toolCallChatResponse 里解析 toolCall 列表
        //   2. 对每个 toolCall：
        //      a. 根据 name 找到对应的 ToolCallback（比如 "searchWeb" → WebSearchTool）
        //      b. 解析 arguments，转成方法参数（比如 query="Spring AI"）
        //      c. 反射调用 Java 方法（比如 webSearchTool.searchWeb("Spring AI")）
        //      d. 收集返回值
        //   3. 把所有结果打包成 ToolExecutionResult
        Prompt prompt = new Prompt(getMessageList(), this.chatOptions);
        ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);

        // --- ② 更新对话上下文 ---
        // toolExecutionResult.conversationHistory() 包含了完整的对话历史：
        //   [...之前的消息, AssistantMessage(AI要调工具), ToolResponseMessage(工具执行结果)]
        //
        // 用这段更新后的历史替换原来的 messageList，
        // 这样下一轮 think() 时，AI 就能看到"我调了什么工具、工具返回了什么"。
        setMessageList(toolExecutionResult.conversationHistory());

        // 从更新后的对话历史中取出最后一条消息（就是刚执行完的工具响应）
        // CollUtil.getLast() 是 Hutool 的工具方法，相当于 list.get(list.size() - 1)
        ToolResponseMessage toolResponseMessage =
                (ToolResponseMessage) CollUtil.getLast(toolExecutionResult.conversationHistory());

        // --- ③ 检查是否调用了终止工具 ---
        // TerminateTool 是一个特殊的工具，它的方法名叫 "doTerminate"。
        // 如果 AI 调用了这个工具，表示"任务完成，不需要继续了"。
        // stream() 遍历所有工具响应，anyMatch() 检查是否有任何一个叫 "doTerminate"。
        boolean terminateToolCalled = toolResponseMessage.getResponses().stream()
                .anyMatch(response -> response.name().equals("doTerminate"));
        if (terminateToolCalled) {
            // AI 主动说"我完事了" → 标记 FINISHED，循环结束
            setState(AgentState.FINISHED);
        }

        // --- ④ 格式化返回结果 ---
        // 把所有工具的执行结果拼成可读的字符串，格式如下：
        //   工具 searchWeb 返回的结果：{"title":"Spring AI",...}
        //   工具 readFile 返回的结果：文件内容...
        String results = toolResponseMessage.getResponses().stream()
                .map(response -> "工具 " + response.name() + " 返回的结果：" + response.responseData())
                .collect(Collectors.joining("\n"));
        log.info(results);
        return results;                                  // 返回给 step()，再通过 SSE 推给前端
    }
}
