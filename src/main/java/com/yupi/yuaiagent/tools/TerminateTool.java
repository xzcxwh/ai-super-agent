package com.yupi.yuaiagent.tools;

// @Tool 注解：标记一个方法为"可被 AI 调用的工具"
import org.springframework.ai.tool.annotation.Tool;

/**
 * TerminateTool（终止工具）—— Agent 的"刹车踏板"
 *
 * 通俗理解：
 *   想象 AI 在一条环形的跑道上跑步，如果没有刹车，它只能一直跑直到力竭（maxSteps）。
 *   这个工具就是给 AI 装了一个"刹车踏板"——AI 觉得任务完成了，就踩下刹车，
 *   主动说"我完事了，停车吧"。
 *
 * 为什么这个工具如此重要？
 *   如果只用 maxSteps 来控制结束，会有两个问题：
 *     1. AI 可能很早就完成了任务，但还要空转剩下的步数（浪费时间和 API 费用）
 *     2. AI 可能永远不知道该停，一直循环到 maxSteps 才被强制终止
 *
 *   有了 TerminateTool，AI 可以在任意时刻主动结束——
 *   "我答复完了" → 调 terminate → for 循环立刻退出 → 干净利落地结束。
 *
 * 工作机制（配合 ToolCallAgent.act() 使用）：
 *
 *   think() 阶段：
 *     AI 回复："任务完成，我帮你搜到了结果并写入了文件"
 *     同时 toolCalls = [{ name: "doTerminate", arguments: {} }]
 *     → think() 返回 true
 *
 *   act() 阶段（ToolCallAgent 第 304-322 行）：
 *     ① 执行 doTerminate() → 返回 "任务结束"（这个返回值实际没被用到）
 *     ② 遍历 toolResponseMessage.responses，检查有没有 name 等于 "doTerminate"
 *     ③ 找到了 → setState(AgentState.FINISHED)
 *     ④ BaseAgent 的 for 循环条件不满足 → 退出循环 → 结束
 *
 *   重点：终止的"魔力"不在这个方法里（它只是返回一句话），
 *   而在 ToolCallAgent.act() 里的检测逻辑。
 *   这个方法只是一个"标记"——AI 调了它 = AI 想停了 = act() 负责真的停下来。
 *
 * 对比：如果没有 TerminateTool
 *   用户问"你好"，AI 只需简单回复一句话。
 *   但 for 循环还在跑 → 第 2 轮 think() 又加了 nextStepPrompt → AI 困惑
 *   → 可能反复输出无意义内容 → 直到 maxSteps 才停 → 浪费 20 轮 API 调用。
 *
 *   有了 TerminateTool + think() 返回 false 的判断：
 *   用户问"你好" → AI 纯文字回复 → toolCallList 为空 → FINISHED，干净利落。
 */
public class TerminateTool {

    /**
     * 终止工具的唯一方法
     *
     * @Tool description 用英文文本块（"""..."""）写成，清晰告诉 AI 什么时候该调这个工具：
     *   "当请求已经满足，或助手无法继续推进任务时，调用此工具终止交互。
     *    当你完成所有任务后，调用此工具结束工作。"
     *
     * 这个方法本身只返回一句话 "任务结束"。
     * 它的真正作用不是这个返回值，而是"方法被调用"这件事本身——
     * ToolCallAgent.act() 检测到 doTerminate 被调用了，就设 FINISHED 状态。
     *
     * 为什么方法名叫 doTerminate 而不是 terminate？
     *   Spring AI 会把方法名作为工具的 name 传给 AI。
     *   这个工具在 act() 里通过 name 来检测：
     *     response.name().equals("doTerminate")
     *   所以方法名和 act() 里的字符串必须一致。
     *
     * @return 简单的终止确认文本（实际被 act() 的检测逻辑替代了）
     */
    @Tool(description = """
            Terminate the interaction when the request is met OR if the assistant cannot proceed further with the task.
            "When you have finished all the tasks, call this tool to end the work.
            """)
    public String doTerminate() {
        // 这个方法体只有一行，因为"终止"的真正逻辑不在这里。
        // act() 会通过检查工具调用的 name 是否为 "doTerminate" 来决定是否停止。
        return "任务结束";
    }
}
