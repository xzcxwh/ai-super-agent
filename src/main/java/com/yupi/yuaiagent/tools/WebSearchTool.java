package com.yupi.yuaiagent.tools;

// Hutool 的 HTTP 工具类：发送 HTTP 请求（比原生 HttpURLConnection 方便得多）
import cn.hutool.http.HttpUtil;
// Hutool 的 JSON 数组
import cn.hutool.json.JSONArray;
// Hutool 的 JSON 对象
import cn.hutool.json.JSONObject;
// Hutool 的 JSON 工具类：解析 JSON 字符串
import cn.hutool.json.JSONUtil;
// @Tool 注解：标记方法为 AI 可调用的工具
import org.springframework.ai.tool.annotation.Tool;
// @ToolParam 注解：描述参数，让 AI 知道该填什么
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
// Stream 流式处理：Collectors.joining() 把列表拼成字符串
import java.util.stream.Collectors;

/**
 * WebSearchTool（网页搜索工具）—— AI 的"搜索引擎"
 *
 * 通俗理解：
 *   AI 自己没有搜索引擎，不能真的去"百度一下"。
 *   这个工具给 AI 接上了百度搜索的能力——
 *   AI 说"搜 Spring AI"，程序调 searchapi.io 的 API 真的去搜，
 *   把前 5 条结果返回给 AI。
 *
 * 和 FileOperationTool 的区别：
 *   FileOperationTool 工作在本机（读写本地文件）
 *   WebSearchTool 工作在外部（调用第三方搜索 API）
 *
 * 为什么需要 apiKey？
 *   searchapi.io 是一个付费/免费增值的搜索 API 服务。
 *   调用它的接口需要密钥来验证身份和计费。
 *   apiKey 从 application.yml 的 search-api.api-key 读取，
 *   在 ToolRegistration 里传给构造函数。
 *
 * 数据流：
 *   用户: "搜一下 Spring AI"
 *     → AI 决定调 searchWeb(query="Spring AI")
 *     → 本方法调 searchapi.io → 百度搜索
 *     → 返回 JSON 响应
 *     → 提取前 5 条 organic_results
 *     → 转成字符串返回给 AI
 *     → AI 基于搜索结果总结回答
 */
public class WebSearchTool {

    /**
     * 搜索 API 的接口地址
     *
     * searchapi.io 是一个第三方搜索 API 服务，
     * 它帮你封装了 Google、Baidu、Bing 等搜索引擎的爬取逻辑。
     * 你只需要发一个 HTTP GET 请求，带上 api_key 和搜索关键词，它返回 JSON 结果。
     *
     * static final = 类级别常量，所有实例共享，不会被修改。
     */
    private static final String SEARCH_API_URL = "https://www.searchapi.io/api/v1/search";

    /**
     * 搜索 API 的密钥
     *
     * 从构造函数传入，构造函数的值来自 application.yml 的 search-api.api-key。
     *
     * final = 一旦构造函数设置好，就不能被修改（密钥泄露是大忌）。
     * private = 只有本类内部能访问。
     */
    private final String apiKey;

    /**
     * 构造函数：传入搜索 API 的密钥
     *
     * 被 ToolRegistration 调用：
     *   WebSearchTool webSearchTool = new WebSearchTool(searchApiKey);
     *
     * 和其他工具的区别：FileOperationTool、TerminateTool 等不需要构造函数参数，
     * 但 WebSearchTool 需要外部 API Key，所以必须通过构造函数传入。
     *
     * @param apiKey searchapi.io 的 API 密钥
     */
    public WebSearchTool(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * 执行百度搜索
     *
     * AI 调用时机：
     *   当用户问"帮我搜一下XXX"或"XX是什么"时，
     *   AI 对照 description 判断需要调这个工具，然后传 query 参数进来。
     *
     * @Tool description 翻译：
     *   "从百度搜索引擎搜索信息"
     *
     * @param query AI 提取的搜索关键词（比如用户说"搜 Spring AI"，AI 传 "Spring AI"）
     * @return 前 5 条搜索结果的 JSON 字符串，或错误信息
     */
    @Tool(description = "Search for information from Baidu Search Engine")
    public String searchWeb(
            @ToolParam(description = "Search query keyword") String query
    ) {
        // --- ① 构建请求参数 ---
        // HashMap 是 Java 的键值对容器，这里用来组装 HTTP GET 的 query 参数
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("q", query);                            // 搜索关键词（q = query）
        paramMap.put("api_key", apiKey);                     // API 密钥（用于身份验证）
        paramMap.put("engine", "baidu");                     // 搜索引擎：百度（也可换成 google、bing）

        try {
            // --- ② 发送 HTTP GET 请求 ---
            // HttpUtil.get(url, params) 是 Hutool 封装的 HTTP GET 方法。
            // 它会自动把 paramMap 拼成 URL 参数，比如：
            //   https://www.searchapi.io/api/v1/search?q=Spring+AI&api_key=xxx&engine=baidu
            // 比自己写 HttpURLConnection 省了大约 10 行代码。
            String response = HttpUtil.get(SEARCH_API_URL, paramMap);

            // --- ③ 解析 JSON 响应 ---
            // searchapi.io 返回的 JSON 结构大致如下：
            //   {
            //     "organic_results": [
            //       { "title": "Spring AI 官方文档", "link": "https://...", "snippet": "..." },
            //       { "title": "Spring AI 入门教程", "link": "https://...", "snippet": "..." },
            //       ...
            //     ]
            //   }
            JSONObject jsonObject = JSONUtil.parseObj(response);  // 把 JSON 字符串解析成 JSONObject

            // 提取 "organic_results" 字段（自然搜索结果，排除了广告）
            JSONArray organicResults = jsonObject.getJSONArray("organic_results");

            // --- ④ 只取前 5 条 ---
            // subList(0, 5) 截取前 5 条。
            // 不全部返回的原因：
            //   1. AI 的上下文有限（太多结果会占用大量 token）
            //   2. 用户通常只需要前几条就够了
            //   3. 能减少 API 费用
            List<Object> objects = organicResults.subList(0, 5);

            // --- ⑤ 格式化为字符串 ---
            // Stream + map + collect 三步走：
            //   stream()               → 把列表转成流
            //   .map(obj -> {...})     → 把每个 JSONObject 转成字符串
            //   .collect(joining(",")) → 用逗号把所有结果串起来
            String result = objects.stream().map(obj -> {
                JSONObject tmpJSONObject = (JSONObject) obj;     // Object 转型为 JSONObject
                return tmpJSONObject.toString();                 // 转成 JSON 字符串
            }).collect(Collectors.joining(","));                 // 用逗号拼接

            return result;                                       // 返回给 AI

        } catch (Exception e) {
            // API 调用失败（网络问题、API Key 无效、额度用完等）
            // 返回错误字符串给 AI，让 AI 可以告知用户或尝试其他方法
            return "Error searching Baidu: " + e.getMessage();
        }
    }
}
