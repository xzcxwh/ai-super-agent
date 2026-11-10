package com.yupi.yuaiagent.tools;

// Spring AI 的工具工具类：把多个 ToolCallback 对象合并成一个数组
import org.springframework.ai.support.ToolCallbacks;
// 工具回调：每个带 @Tool 注解的方法会被包装成 ToolCallback，代表一个可被 AI 调用的工具
import org.springframework.ai.tool.ToolCallback;
// @Value：从 application.yml 里读取配置值
import org.springframework.beans.factory.annotation.Value;
// @Bean：标记一个方法，告诉 Spring "这个方法的返回值交给你管理"
import org.springframework.context.annotation.Bean;
// @Configuration：标记一个类是"配置类"，Spring 启动时会扫描并执行里面的 @Bean 方法
import org.springframework.context.annotation.Configuration;

/**
 * ToolRegistration（工具注册中心）—— 工具的"总装车间"
 *
 * 通俗理解：
 *   如果 AI 是厨师，工具就是厨房里的锅碗瓢盆。
 *   这个类就是一个"仓库管理员"——把所有工具集中在这里登记造册，
 *   Spring 启动时自动清点，然后交给 AiController 和 LoveApp 使用。
 *
 * 为什么需要一个专门的注册类？
 *   1. 集中管理：所有工具一目了然，要加新工具只需在这里加一行
 *   2. 统一装配：有些工具需要外部参数（比如 WebSearchTool 需要 API Key），
 *      这里统一处理，不用散落在各处
 *   3. Spring 集成：通过 @Bean 让工具数组可以被注入到任何需要它的地方
 *
 * 这些工具最终去了哪里？
 *   - AiController.doChatWithManus() → new YuManus(allTools, ...) → Agent 的工具弹药库
 *   - LoveApp.doChatWithTools() → .toolCallbacks(allTools) → AI 可以调这些工具
 *
 * 加新工具的步骤（三步）：
 *   1. 创建一个新类，写好 @Tool 方法（参考 FileOperationTool.java）
 *   2. 在这个类的 allTools() 里 new 一个实例
 *   3. 在 ToolCallbacks.from() 里加上它
 *   完成！不需要改任何其他代码，AI 自动就能用新工具了。
 */
@Configuration  // ← 告诉 Spring：这是一个配置类，启动时扫描并执行里面的 @Bean 方法
public class ToolRegistration {

    /**
     * 搜索 API 的密钥
     *
     * @Value("${search-api.api-key}") 的含义：
     *   从 application.yml（或 application-local.yml）里读取 search-api.api-key 的值，
     *   自动赋值给这个字段。
     *
     * ${...} 是 Spring 的"属性占位符"语法，
     * search-api.api-key 对应 yml 里的：
     *   search-api:
     *     api-key: 你的 API Key
     *
     * 这个 Key 目前是占位符，只有调用百度搜索工具时才会用到。
     */
    @Value("${search-api.api-key}")
    private String searchApiKey;

    /**
     * 集中注册所有 AI 可用的工具
     *
     * @Bean 的含义：
     *   这个方法返回的 ToolCallback[] 数组会被 Spring 自动管理起来。
     *   其他地方（AiController、LoveApp）通过 @Resource 注入时，
     *   拿到的就是这个方法返回的同一个数组（默认单例）。
     *
     * 为什么用 @Bean 而不是 @Component？
     *   这里不是标记一个"类"为组件，而是标记一个"方法的返回值"为 Bean。
     *   工厂模式：这个方法就是工厂，生产出一个配置好的工具数组。
     *
     * ToolCallbacks.from() 的作用：
     *   这是 Spring AI 提供的工具方法。
     *   它会扫描每个传入的 Java 对象，找到里面打了 @Tool 注解的方法，
     *   把它们包装成 ToolCallback 对象，最后打包成一个数组返回。
     *
     *   比如 FileOperationTool 有 readFile() 和 writeFile() 两个 @Tool 方法，
     *   ToolCallbacks.from() 会自动生成两个 ToolCallback：
     *     - ToolCallback("readFile", ...)
     *     - ToolCallback("writeFile", ...)
     *
     * @return 所有工具打包成的数组
     */
    @Bean  // ← 告诉 Spring：这个方法的返回值交给你管理，放到容器里
    public ToolCallback[] allTools() {

        // --- 逐个创建工具实例 ---

        // 文件操作工具：提供 readFile() 和 writeFile() 两个工具
        FileOperationTool fileOperationTool = new FileOperationTool();

        // 百度搜索工具：需要传入 API Key（从 application.yml 读取）
        // 注意：WebSearchTool 的构造函数需要 apiKey，其他工具都不需要参数
        WebSearchTool webSearchTool = new WebSearchTool(searchApiKey);

        // 网页抓取工具：用 Jsoup 抓取指定 URL 的网页内容
        WebScrapingTool webScrapingTool = new WebScrapingTool();

        // 资源下载工具：下载图片、文件等
        ResourceDownloadTool resourceDownloadTool = new ResourceDownloadTool();

        // 终端操作工具：执行系统命令（如 dir、ping 等）
        TerminalOperationTool terminalOperationTool = new TerminalOperationTool();

        // PDF 生成工具：用 iText 库生成 PDF 文件
        PDFGenerationTool pdfGenerationTool = new PDFGenerationTool();

        // 获取当前时间工具：你自己的第一个工具！
        GetCurrentTimeTool getCurrentTimeTool = new GetCurrentTimeTool();

        // 终止工具：让 AI 可以主动说"我完事了"
        // 这是最关键的工具之一——没有它，Agent 不知道什么时候该停
        TerminateTool terminateTool = new TerminateTool();

        // --- 打包返回 ---
        // ToolCallbacks.from() 扫描每个对象的 @Tool 方法，
        // 包装成 ToolCallback[] 数组，交给 Spring 管理
        return ToolCallbacks.from(
                fileOperationTool,       // → readFile, writeFile
                webSearchTool,           // → searchWeb
                webScrapingTool,         // → scrapeWeb
                resourceDownloadTool,    // → downloadResource
                terminalOperationTool,   // → executeCommand
                pdfGenerationTool,       // → generatePDF
                getCurrentTimeTool,      // → getCurrentTime（你的第一个工具！）
                terminateTool            // → doTerminate（最特殊的工具！）
        );
    }
}
