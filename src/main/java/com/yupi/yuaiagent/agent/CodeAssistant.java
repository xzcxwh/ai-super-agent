package com.yupi.yuaiagent.agent;

import com.yupi.yuaiagent.advisor.MyLoggerAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

/**
 * CodeAssistant（代码助手 Agent）—— 你的第二个 Agent！
 *
 * 和 YuManus 的唯一区别：换了 Prompt。
 * 所有 Think-Act-工具调用能力完全复用父类，一行逻辑都没写。
 */
public class CodeAssistant extends ToolCallAgent {

    public CodeAssistant(ToolCallback[] allTools, ChatModel dashscopeChatModel) {
        super(allTools);
        this.setName("codeAssistant");

        // 唯一的不同：系统提示词换成"代码助手"的人设
        String SYSTEM_PROMPT = """
                You are CodeAssistant, a professional programming assistant.
                Your expertise includes all programming languages, frameworks, debugging, and software design.
                When asked to write code, always provide complete, runnable examples with explanations.
                When asked to fix bugs, analyze the root cause first, then provide the fix.
                You have various tools at your disposal to efficiently complete complex requests.
                """;
        this.setSystemPrompt(SYSTEM_PROMPT);

        // 行动指南保持不变——让 AI 知道怎么选工具
        String NEXT_STEP_PROMPT = """
                Based on user needs, proactively select the most appropriate tool or combination of tools.
                For complex tasks, you can break down the problem and use different tools step by step to solve it.
                After using each tool, clearly explain the execution results and suggest the next steps.
                If you want to stop the interaction at any point, use the `terminate` tool/function call.
                """;
        this.setNextStepPrompt(NEXT_STEP_PROMPT);
        this.setMaxSteps(20);

        // 和 YuManus 完全一样的 ChatClient 配置
        ChatClient chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();
        this.setChatClient(chatClient);
    }
}
