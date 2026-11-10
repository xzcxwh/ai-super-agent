package com.yupi.yuaiagent.agent;

// Lombok：自动生成 getter/setter
import lombok.Data;
// Lombok：生成 equals/hashCode 时包含父类的字段（callSuper = true）
import lombok.EqualsAndHashCode;
// Lombok：自动生成 log 对象
import lombok.extern.slf4j.Slf4j;
// Spring AI 的助手消息：代表 AI 的回复（可能是文字，也可能是"我要调工具"的请求）
import org.springframework.ai.chat.messages.AssistantMessage;
// Spring AI 的消息接口：对话上下文里每条消息的基类
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * ReActAgent（ReAct 模式的代理抽象类）—— Agent 的"大脑中枢"
 *
 * ReAct 是什么？
 *   ReAct = Reasoning（推理）+ Acting（行动）
 *   这是 AI Agent 领域最经典的范式之一：
 *     1. 先"想"：AI 看看当前情况，决定要不要动手
 *     2. 再"做"：如果需要动手，就调用工具干实事
 *     3. 做完后再"想"：看看工具结果，决定下一步
 *
 * 通俗理解：
 *   BaseAgent 是一台"跑步机"（管跑多久、跑多快），
 *   ReActAgent 给跑步机加上了"大脑"——每一步拆成"想"和"做"两个动作。
 *
 *   step() = think() + act()
 *     think() → 要不要调工具？调哪个？
 *     act()   → 执行工具，拿到结果
 *
 * 设计模式：模板方法模式（Template Method）
 *   本类实现了 step() 的流程骨架（先 think 后 act），
 *   但 think() 和 act() 仍然是抽象的——留给下一个子类（ToolCallAgent）实现。
 *
 * 为什么这个类还是 abstract？
 *   因为它只定义了"想完就做"的流程，
 *   但"怎么想"（think）和"怎么做"（act）还没有实现。
 *   下一个子类 ToolCallAgent 会用 Spring AI 来具体实现这两个方法。
 */
@EqualsAndHashCode(callSuper = true)  // ← 生成 equals/hashCode 时包含父类(BaseAgent)的字段
@Data                                  // ← 生成所有字段的 getter/setter
@Slf4j                                 // ← 生成 log 对象
public abstract class ReActAgent extends BaseAgent {   // extends BaseAgent：继承状态机、循环、消息上下文

    // ========================================================================
    // 第一部分：两个抽象方法 —— "填空题"，留给 ToolCallAgent 去实现
    // ========================================================================

    /**
     * 思考阶段：处理当前状态并决定下一步行动
     *
     * 这个方法是 ReAct 模式的"大脑"——Agent 在这里决定它接下来要干什么。
     *
     * 返回值的含义：
     *   true  → AI 需要调用工具，接下来要执行 act()
     *   false → AI 不需要工具，直接文字回复用户，对话结束
     *
     * 注意：这个方法是 abstract 的，这里只有声明没有实现。
     * 真正的实现在 ToolCallAgent 里：调大模型 → 解析返回结果 → 判断有没有 toolCalls。
     *
     * @return true = 需要行动（调工具），false = 不需要行动（对话完成）
     */
    public abstract boolean think();

    /**
     * 行动阶段：执行决定的行动
     *
     * 当 think() 返回 true 时，调用这个方法去真正执行工具。
     *
     * 真正的实现在 ToolCallAgent 里：
     *   拿到 think() 阶段 AI 返回的工具调用请求，
     *   执行对应的 Java 方法（搜索、读写文件、生成 PDF...），
     *   把工具结果写入 messageList 供下一轮 think() 参考。
     *
     * @return 行动执行结果的文本（比如"工具 searchWeb 返回的结果：..."）
     */
    public abstract String act();

    // ========================================================================
    // 第二部分：step() —— 把"想"和"做"串起来的模板方法
    // ========================================================================

    /**
     * 执行单个步骤：先思考，再行动
     *
     * 这个方法是 ReAct 模式的核心执行逻辑，由 BaseAgent 的 for 循环调用。
     * 它把一步拆成了两个阶段：
     *
     *   think() 返回 true  → 需要调工具 → 执行 act()
     *   think() 返回 false → 不需要工具 → 从消息历史取 AI 的文字回复
     *
     * @Override 表示"重写父类方法"——BaseAgent 里 step() 是抽象的，这里给它一个具体实现。
     *
     * 调用链：
     *   BaseAgent.runStream() 的 for 循环
     *     → step()             ← 你在这里
     *       → think()          ← 子类 ToolCallAgent 实现
     *       → act() 或 getAiResponseText()
     *
     * @return 这一步的结果文本（要么是 AI 的文字回复，要么是工具执行结果）
     */
    @Override
    public String step() {
        try {
            // --- ① 思考 ---
            // 调用 think()，让 AI 看看当前情况，决定要不要调工具。
            // think() 内部会调用大模型 API，拿到 AI 的回复，
            // 并把回复（AssistantMessage）存入 messageList。
            boolean shouldAct = think();

            // --- ② 判断 ---
            if (!shouldAct) {
                // AI 决定不需要工具 → 对话结束。
                // 从 messageList 里找到 AI 最后一条文字回复，返回给用户。
                return getAiResponseText();
            }

            // --- ③ 行动 ---
            // AI 决定要调工具 → 执行 act()。
            // act() 会执行真实的方法（如搜索、写文件），
            // 并把工具结果写回 messageList，供下一轮 think() 参考。
            return act();

        } catch (Exception e) {
            // 万一 think() 或 act() 抛出异常（比如网络断了、API 挂了），
            // 这里兜底，打印错误但不会让整个程序崩溃。
            e.printStackTrace();
            return "步骤执行失败：" + e.getMessage();
        }
    }

    // ========================================================================
    // 第三部分：getAiResponseText() —— 从对话历史里翻出 AI 的最终回答
    // ========================================================================

    /**
     * 从消息列表中获取 AI 最后一条文本回复
     *
     * 为什么需要这个辅助方法？
     *   当 think() 返回 false 时，表示 AI 直接文字回复了，没有调工具。
     *   但 AI 的回复已经存在 messageList 里了（think() 内部 add 进去的），
     *   我们需要从 messageList 里把它翻出来，返回给用户。
     *
     * 查找逻辑：
     *   从 messageList 的末尾往开头遍历（倒序），
     *   找到第一个"有文字内容"的 AssistantMessage（AI 的消息），
     *   把它的文本取出来返回。
     *
     * 为什么要倒序？
     *   messageList 里可能有多个 AssistantMessage（多轮 Think-Act 中产生的），
     *   最后一条才是 AI 对用户的最终回复。
     *
     * 兜底：
     *   如果遍历完都没找到合适的内容，返回 "思考完成 - 无需行动"。
     *
     * @return AI 的文本回复
     */
    private String getAiResponseText() {
        List<Message> messages = getMessageList();             // 拿到对话上下文（继承自 BaseAgent）

        // 从后往前遍历 messageList（最后一条消息是最新的）
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);                     // 取出第 i 条消息

            // instanceof 模式匹配（Java 16+ 语法）：
            // 判断 msg 是不是 AssistantMessage 类型，
            // 如果是，直接把它转成 AssistantMessage 并赋值给 assistantMsg
            if (msg instanceof AssistantMessage assistantMsg) {
                String text = assistantMsg.getText();          // 拿到 AI 回复的纯文本
                if (text != null && !text.isBlank()) {         // 不是 null，也不是空字符串
                    return text;                               // 找到了！直接返回
                }
            }
        }

        // 走到这里说明 messageList 里没有任何有效的 AI 回复
        // 正常情况不会走到这里，这是一个兜底
        return "思考完成 - 无需行动";
    }

}
