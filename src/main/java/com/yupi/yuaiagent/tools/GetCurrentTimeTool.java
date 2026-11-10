package com.yupi.yuaiagent.tools;

import org.springframework.ai.tool.annotation.Tool;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 获取当前时间的工具 —— 你自己的第一个工具！
 */
public class GetCurrentTimeTool {

    @Tool(description = "Get the current date and time. Use this when the user asks about the current time, today's date, or what time it is now.")
    public String getCurrentTime() {
        return LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
