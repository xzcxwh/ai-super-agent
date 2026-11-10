package com.yupi.yuaiagent.agent;

// ==================== import 区域 ====================

// Hutool 工具库：判断字符串是否为空
import cn.hutool.core.util.StrUtil;
// Agent 的 4 种状态枚举（空闲、运行中、已完成、出错）
import com.yupi.yuaiagent.agent.model.AgentState;
// Lombok：自动生成 getter/setter（不用手写一堆模板代码）
import lombok.Data;
// Lombok：自动生成 log 对象，可以直接用 log.info() 打印日志
import lombok.extern.slf4j.Slf4j;
// Spring AI 的聊天客户端：用来和大模型对话
import org.springframework.ai.chat.client.ChatClient;
// Spring AI 的消息接口：对话中的每一条消息（用户消息、AI 消息、工具消息）
import org.springframework.ai.chat.messages.Message;
// Spring AI 的用户消息：代表"用户说的话"
import org.springframework.ai.chat.messages.UserMessage;
// Spring MVC 的 SSE 发射器：用于向浏览器推送流式数据
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
// CompletableFuture：Java 的异步编程工具，让代码在后台线程执行
import java.util.concurrent.CompletableFuture;

/**
 * BaseAgent（抽象基础代理类）—— 所有 Agent 的"骨架"
 *
 * 通俗理解：
 *   这个类就像一个工厂的"流水线框架"。
 *   它规定了"怎么跑"（状态机、循环、步数控制），
 *   但"每一步做什么事"留给子类去实现（抽象方法 step()）。
 *
 * 继承关系：
 *   BaseAgent          ← 你在这里
 *     └─ ReActAgent    ← step() = think() + act()
 *          └─ ToolCallAgent ← think() 调 AI、act() 执行工具
 *               └─ YuManus    ← 填上 Prompt 和工具列表
 *
 * 核心设计模式：模板方法模式（Template Method）
 *   父类定义好执行流程（run / runStream），
 *   子类只需要实现 step() 这一个方法，流程自动跑起来。
 *
 * 状态机（Agent 的一生）：
 *   IDLE（空闲）→ RUNNING（运行中）→ FINISHED（完成）
 *                                   → ERROR（出错）
 *
 * abstract 关键字表示"抽象类"，不能直接 new BaseAgent()，
 * 必须创建它的子类才能使用。
 */
@Data    // ← Lombok：编译时自动生成所有字段的 getXxx() 和 setXxx() 方法
@Slf4j   // ← Lombok：编译时自动生成 log 对象
public abstract class BaseAgent {

    // ========================================================================
    // 第一部分：Agent 的核心属性（"出厂设置"）
    // ========================================================================

    /**
     * Agent 的名字
     * 子类在构造函数里设置，比如 YuManus 设置成 "yuManus"
     * 用于日志区分是哪个 Agent 在干活
     */
    private String name;

    /**
     * 系统提示词：给 AI 的"人设"
     * 比如 "你是 YuManus，一个全能 AI 助手..."
     * 会在每次调用大模型时附上，告诉 AI 它是谁、要干什么
     */
    private String systemPrompt;

    /**
     * 下一步提示词：指导 AI 怎么选工具的"行动指南"
     * 比如 "基于用户需求，主动选择最合适的工具..."
     * 只在第一次思考时加到对话上下文里
     */
    private String nextStepPrompt;

    /**
     * Agent 的当前状态
     * AgentState.IDLE 表示"闲着，可以接新任务"
     * 初始值 IDLE，执行时变成 RUNNING，结束变成 FINISHED 或 ERROR
     */
    private AgentState state = AgentState.IDLE;

    /**
     * 当前执行到第几步了
     * 从 1 开始计数，每执行一步就 +1
     */
    private int currentStep = 0;

    /**
     * 最多执行多少步
     * 防止 AI 陷入死循环（比如反复调同一个工具）。
     * 默认 10 步，YuManus 里改成了 20 步。
     * 达到上限后强制停止，状态变为 FINISHED。
     */
    private int maxSteps = 10;

    /**
     * 大模型聊天客户端
     * 这是和 AI 大模型对话的统一入口。
     * 子类（YuManus）在构造函数里创建并设置。
     */
    private ChatClient chatClient;

    /**
     * 对话上下文（消息列表）
     *
     * 这是 Agent 的"记忆"——它记住这次对话中发生过的所有事情。
     * 里面按顺序存了：
     *   UserMessage              用户说了什么
     *   AssistantMessage         AI 回复了什么、AI 想调哪个工具
     *   ToolResponseMessage      工具执行的结果是什么
     *
     * 每次调用大模型时，整个 messageList 会作为"上下文"发给 AI，
     * 这样 AI 才知道之前聊了什么、之前调工具的结果是什么。
     *
     * 为什么用 new ArrayList<>() 初始化？
     *   确保字段永远不会是 null，省的每次用之前都判空。
     */
    private List<Message> messageList = new ArrayList<>();

    // ========================================================================
    // 第二部分：run() —— 同步执行模式（一次性返回全部结果）
    // ========================================================================

    /**
     * 同步运行 Agent
     *
     * 工作流程：
     *   ① 检查状态（必须是 IDLE）和参数（消息不能为空）
     *   ② 把状态改成 RUNNING，把用户消息存入 messageList
     *   ③ 开始 for 循环：每一步调 step()，结果存到列表里
     *   ④ 状态变为 FINISHED 或超过最大步数 → 退出循环
     *   ⑤ 把所有步骤的结果用换行符拼起来，一次性返回
     *
     * 和 runStream() 的区别：
     *   run()      返回 String，等全部执行完才给你结果
     *   runStream() 返回 SseEmitter，每执行一步就推送给前端
     *
     * @param userPrompt 用户发的消息
     * @return 所有步骤的结果拼在一起的文本
     * @throws RuntimeException 如果状态不对或消息为空
     */
    public String run(String userPrompt) {
        // --- ① 基础校验 ---
        // 只有 IDLE 状态才能启动，如果已经在 RUNNING 就抛异常
        if (this.state != AgentState.IDLE) {
            throw new RuntimeException("Cannot run agent from state: " + this.state);
        }
        // 消息不能为空（StrUtil.isBlank 判断 null、空字符串、纯空格）
        if (StrUtil.isBlank(userPrompt)) {
            throw new RuntimeException("Cannot run agent with empty user prompt");
        }

        // --- ② 状态切换 + 初始化 ---
        this.state = AgentState.RUNNING;                     // 状态：空闲 → 运行中
        messageList.add(new UserMessage(userPrompt));         // 把用户消息加入对话上下文
        List<String> results = new ArrayList<>();              // 用来收集每一步的结果

        try {
            // --- ③ 核心循环 ---
            // 条件：没到最大步数 且 状态不是 FINISHED
            // 两个条件任意一个不满足就退出循环
            for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
                int stepNumber = i + 1;                      // stepNumber 从 1 开始，给人看的
                currentStep = stepNumber;                     // 更新当前步数
                log.info("Executing step {}/{}", stepNumber, maxSteps);

                String stepResult = step();                   // ← 调抽象方法！具体逻辑在子类
                String result = "Step " + stepNumber + ": " + stepResult;
                results.add(result);                          // 拼上步骤编号，存起来
            }

            // --- ④ 循环结束后的处理 ---
            // 如果是因为达到最大步数退出的（不是正常完成），额外加一条提示
            if (currentStep >= maxSteps) {
                state = AgentState.FINISHED;
                results.add("Terminated: Reached max steps (" + maxSteps + ")");
            }
            return String.join("\n", results);                // 用换行符拼成一个大字符串返回

        } catch (Exception e) {
            // --- ⑤ 异常处理 ---
            state = AgentState.ERROR;                         // 状态改为"出错"
            log.error("error executing agent", e);
            return "执行错误" + e.getMessage();

        } finally {
            // --- ⑥ 收尾 ---
            // finally 块不管正常还是异常都会执行
            // cleanup() 是留给子类的"打扫卫生"方法
            this.cleanup();
        }
    }

    // ========================================================================
    // 第三部分：runStream() —— 流式执行模式（边执行边推送，体验好）
    // ========================================================================

    /**
     * 流式运行 Agent（核心方法，AiController 调的就是这个）
     *
     * 和 run() 的核心区别：
     *   run()      执行完才返回 → 用户等很久
     *   runStream() 每一步都推送 → 用户实时看到进度
     *
     * SseEmitter 是什么？
     *   Spring 提供的一个工具，它可以保持 HTTP 连接不关闭，
     *   随时往里面塞数据，浏览器实时收到。
     *   类比：run() 是发邮件（写完才发），runStream() 是打电话（边说边听）。
     *
     * CompletableFuture.runAsync() 是什么？
     *   Java 的异步工具。它会把括号里的代码放到一个"后台线程"去跑，
     *   当前线程（HTTP 请求线程）立即返回 SseEmitter 给前端，
     *   不阻塞等待 Agent 执行完。
     *
     * 为什么要异步？
     *   Agent 的执行可能需要几十秒（多轮 Think-Act），
     *   如果同步执行，HTTP 线程会被卡住几十秒，前端一直白屏。
     *   异步执行后：前端立即拿到 SseEmitter 连接，然后一条条收到推送。
     *
     * @param userPrompt 用户发的消息
     * @return SseEmitter 对象（一个持续可写的"管道"）
     */
    public SseEmitter runStream(String userPrompt) {
        // ① 创建 SseEmitter，设置超时 300000 毫秒 = 5 分钟
        //    如果 5 分钟内没任何数据推送，连接自动断开
        SseEmitter sseEmitter = new SseEmitter(300000L);

        // ② 异步执行：把 Agent 的执行逻辑放到后台线程
        //    这样当前线程可以立刻 return sseEmitter 给前端
        CompletableFuture.runAsync(() -> {

            // --- 基础校验（和 run() 一样） ---
            try {
                if (this.state != AgentState.IDLE) {
                    sseEmitter.send("错误：无法从状态运行代理：" + this.state);
                    sseEmitter.complete();
                    return;
                }
                if (StrUtil.isBlank(userPrompt)) {
                    sseEmitter.send("错误：不能使用空提示词运行代理");
                    sseEmitter.complete();
                    return;
                }
            } catch (Exception e) {
                sseEmitter.completeWithError(e);
            }

            // --- 状态切换 + 初始化 ---
            this.state = AgentState.RUNNING;
            messageList.add(new UserMessage(userPrompt));    // 加入用户消息到上下文
            List<String> results = new ArrayList<>();

            try {
                // --- 核心循环（和 run() 逻辑一样，区别是每步都 sseEmitter.send()） ---
                for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
                    int stepNumber = i + 1;
                    currentStep = stepNumber;
                    log.info("Executing step {}/{}", stepNumber, maxSteps);

                    String stepResult = step();              // ← 调抽象方法
                    results.add(stepResult);
                    sseEmitter.send(stepResult);             // ← 关键！实时推送给前端
                }

                // --- 检查是否因达到最大步数而退出 ---
                if (currentStep >= maxSteps) {
                    state = AgentState.FINISHED;
                    results.add("Terminated: Reached max steps (" + maxSteps + ")");
                    sseEmitter.send("执行结束：达到最大步骤（" + maxSteps + "）");
                }

                // 正常结束，关闭 SSE 连接
                sseEmitter.complete();

            } catch (Exception e) {
                // 执行过程中出错
                state = AgentState.ERROR;
                log.error("error executing agent", e);
                try {
                    sseEmitter.send("执行错误：" + e.getMessage());
                    sseEmitter.complete();
                } catch (IOException ex) {
                    sseEmitter.completeWithError(ex);
                }
            } finally {
                this.cleanup();                              // 打扫卫生
            }
        });  // ← CompletableFuture.runAsync 结束

        // ③ 注册超时回调：如果 5 分钟到了还没结束，触发这个
        sseEmitter.onTimeout(() -> {
            this.state = AgentState.ERROR;
            this.cleanup();
            log.warn("SSE connection timeout");
        });

        // ④ 注册完成回调：无论正常还是异常结束，都触发这个
        sseEmitter.onCompletion(() -> {
            if (this.state == AgentState.RUNNING) {
                this.state = AgentState.FINISHED;
            }
            this.cleanup();
            log.info("SSE connection completed");
        });

        // ⑤ 立即返回 SseEmitter（此时后台线程还在跑，连接已建立）
        return sseEmitter;
    }

    // ========================================================================
    // 第四部分：抽象方法 —— 留给子类实现的"填空题"
    // ========================================================================

    /**
     * 执行单个步骤（抽象方法）
     *
     * abstract 关键字意味着"这个方法只有声明，没有实现"。
     * 子类必须重写（@Override）这个方法，告诉程序"每一步到底做什么"。
     *
     * 不同的子类，这一步做的事情完全不同：
     *   ReActAgent.step() = think() + act()
     *     先让 AI 想想（think），如果需要行动就执行（act），不需要就返回 AI 的回复
     *
     * 这就是模板方法模式的核心：
     *   父类管流程（循环、状态），子类管细节（每一步干嘛）。
     *
     * @return 这一步的执行结果文本
     */
    public abstract String step();

    /**
     * 清理资源（钩子方法）
     *
     * 钩子方法 = 有默认空实现，子类可以选择性重写。
     * 和抽象方法不一样，不重写也不会报错。
     *
     * 什么时候重写？
     *   如果子类在执行过程中申请了特殊资源（文件句柄、网络连接等），
     *   可以在 cleanup() 里释放掉。
     *   本项目暂时没用到，保留作为扩展点。
     */
    protected void cleanup() {
        // 子类可以重写此方法来清理资源
    }
}
