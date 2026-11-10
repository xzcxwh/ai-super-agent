package com.yupi.yuaiagent.agent.model;

/**
 * AgentState（Agent 运行状态枚举）—— Agent 的"红绿灯"
 *
 * 通俗理解：
 *   就像红绿灯控制车辆"能不能走、要不要停"，
 *   这个枚举控制 Agent 在 BaseAgent 的 for 循环里"能不能继续、什么时候停"。
 *
 * 什么是枚举（enum）？
 *   enum 是 Java 的一种特殊类，用来定义"有限的几个固定值"。
 *   比如红绿灯只有：红、黄、绿 三个状态，不可能是别的。
 *   用枚举比用数字（0=空闲, 1=运行, 2=完成）更安全、更可读。
 *
 * 状态流转图（Agent 的一生）：
 *
 *   用户发消息
 *      │
 *      ▼
 *   IDLE（空闲）          ← Agent 刚创建，等待任务
 *      │
 *      │ runStream() 被调用
 *      ▼
 *   RUNNING（运行中）      ← Agent 正在执行 Think-Act 循环
 *      │
 *      ├─ think() 返回 false → 不需要工具了
 *      │     │
 *      │     ▼
 *      │  FINISHED（已完成） ← 对话正常结束，for 循环退出
 *      │
 *      ├─ 达到 maxSteps 上限
 *      │     │
 *      │     ▼
 *      │  FINISHED（已完成） ← 被强制停止
 *      │
 *      └─ 异常发生（网络断了、API 挂了...）
 *            │
 *            ▼
 *         ERROR（错误）     ← 出问题了，也会退出循环
 *
 * 枚举在代码中怎么用的？
 *   BaseAgent.java 中的关键用法：
 *     第 107 行  → private AgentState state = AgentState.IDLE;    // 初始状态
 *     第 160 行  → if (this.state != AgentState.IDLE) { ... }    // 只能从 IDLE 启动
 *     第 167 行  → this.state = AgentState.RUNNING;              // 开始运行
 *     第 182 行  → for (... && state != AgentState.FINISHED)     // FINISHED 就退出循环
 *     第 227 行  → setState(AgentState.FINISHED);                // think() 判断不需要工具
 *     第 299 行  → setState(AgentState.FINISHED);                // act() 检测到终止工具
 *     ToolCallAgent 第 240 行 → state = AgentState.ERROR;       // 出错时
 *
 * 为什么不用 boolean（isRunning、isFinished）？
 *   boolean 只能表示两种状态，但 Agent 有 4 种状态。
 *   用多个 boolean 会带来非法组合（比如同时 isRunning=true 且 isFinished=true），
 *   用枚举保证 Agent 永远只处于一种合法状态。
 */
public enum AgentState {    // enum 关键字表示这是一个枚举类（不是普通的 class）

    /**
     * IDLE（空闲状态）—— "我准备好了，随时可以接活"
     *
     * 什么时候是这个状态？
     *   - Agent 刚 new 出来时（BaseAgent 里的初始值）
     *   - 上一个任务已经 cleanup() 了，可以接新任务
     *
     * 能做什么？
     *   只有 IDLE 状态下才能调 run() 或 runStream() 启动新任务。
     *   如果当前不是 IDLE（比如还在 RUNNING），调用 run() 会抛异常。
     *
     * 为什么要有这个状态？
     *   防止一个 Agent 实例被重复启动。每个 Agent 实例只能跑一次任务。
     */
    IDLE,

    /**
     * RUNNING（运行中状态）—— "正在干活，别打扰我"
     *
     * 什么时候变成这个状态？
     *   调 run() 或 runStream() 后，状态立即从 IDLE → RUNNING。
     *
     * 在这个状态下发生什么？
     *   BaseAgent 的 for 循环在跑：
     *     for (i < maxSteps && state != FINISHED)
     *       每轮调 step() → think() → 可能 act()
     *
     * 怎么离开这个状态？
     *   → think() 返回 false（不需要工具）→ FINISHED
     *   → act() 检测到终止工具           → FINISHED
     *   → 达到 maxSteps 上限             → FINISHED
     *   → 发生异常                        → ERROR
     */
    RUNNING,

    /**
     * FINISHED（已完成状态）—— "活干完了，收工！"
     *
     * 什么时候变成这个状态？
     *   - think() 判断 AI 不需要工具了，直接文字回复了用户
     *   - act() 检测到 AI 调用了 terminate 工具（AI 主动说"我完事了"）
     *   - 循环步数达到了 maxSteps 上限（被强制停止）
     *
     * 变成 FINISHED 后会发生什么？
     *   BaseAgent 的 for 循环条件 `state != AgentState.FINISHED` 不满足 → 退出循环。
     *   然后调 cleanup() 打扫卫生，SSE 连接关闭。
     *
     * FINISHED 和 IDLE 的区别？
     *   IDLE = 还没开始干活
     *   FINISHED = 活干完了，但这个实例不会再接新活了（要接新活需要 new 新实例）
     */
    FINISHED,

    /**
     * ERROR（错误状态）—— "出问题了！"
     *
     * 什么时候变成这个状态？
     *   - think() 调 AI 时网络断了
     *   - act() 执行工具时抛异常
     *   - 任何未预期的异常
     *
     * 变成 ERROR 后会发生什么？
     *   和 FINISHED 一样，循环退出、清理资源。
     *   区别是返回给用户的信息不同（"执行错误：xxx" vs 正常回复）。
     *
     * 为什么不直接在 catch 里继续？
     *   如果 AI 调用已经失败了，继续循环只会反复失败、浪费 API 调用次数。
     *   立即停止是更安全的做法。
     */
    ERROR
}
