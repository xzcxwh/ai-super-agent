package com.yupi.yuaiagent.tools;

// Hutool 的文件工具类：读写文件、创建目录等（比自己写 IO 代码方便得多）
import cn.hutool.core.io.FileUtil;
// 本项目统一的文件保存路径常量
import com.yupi.yuaiagent.constant.FileConstant;
// @Tool 注解：标记一个方法为"可被 AI 调用的工具"。
// Spring AI 会扫描这个注解，把方法信息（名称、描述、参数）告诉 AI。
import org.springframework.ai.tool.annotation.Tool;
// @ToolParam 注解：描述工具方法的参数，让 AI 知道"这个参数是干什么的、该填什么值"
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * FileOperationTool（文件操作工具）—— AI 的"纸和笔"
 *
 * 通俗理解：
 *   AI 本身不能读写你电脑上的文件。这个工具给了 AI "纸和笔"——
 *   AI 说要"写"（writeFile），程序就在磁盘上创建一个文件；
 *   AI 说要"读"（readFile），程序就把文件内容读出来给 AI 看。
 *
 * 这是整个项目中最简单的一个工具，适合作为写新工具的模板。
 *
 * 一个工具类需要什么？
 *   1. 一个普通 Java 类（不需要继承任何特殊类，不需要 @Component）
 *   2. 方法上打 @Tool 注解，description 描述"这个工具是干什么的"
 *   3. 参数上打 @ToolParam 注解，description 描述"这个参数该填什么"
 *
 *   description 为什么这么重要？
 *     因为 AI 是靠读 description 来判断"该调哪个工具"的！
 *     如果 description 写得不清楚，AI 就可能选错工具、填错参数。
 *     description 是给 AI 看的"使用说明书"，一定要用英文（AI 对英文理解更好）。
 *
 * 这个工具类的两个能力：
 *   readFile  → AI 说"帮我读一下 xxx.txt" → 程序读取文件内容返回给 AI
 *   writeFile → AI 说"帮我把这段话写到 xxx.txt" → 程序在磁盘上创建文件
 *
 * 文件存在哪里？
 *   FILE_SAVE_DIR = System.getProperty("user.dir") + "/tmp/file"
 *   user.dir 是当前项目根目录，所以文件保存在 项目根目录/tmp/file/ 下。
 */
public class FileOperationTool {

    /**
     * 文件存储目录
     *
     * FileConstant.FILE_SAVE_DIR = System.getProperty("user.dir") + "/tmp"
     *   即：项目根目录/tmp/
     *
     * 这里再加 "/file"，最终目录是：项目根目录/tmp/file/
     *
     * 为什么用 private final？
     *   这个路径是固定的，不需要外部修改，也不需要子类看到。
     *   final = 不可变常量。
     */
    private final String FILE_DIR = FileConstant.FILE_SAVE_DIR + "/file";

    // ========================================================================
    // 工具 1：readFile —— 读取文件
    // ========================================================================

    /**
     * 读取文件内容
     *
     * @Tool 注解做了什么？
     *   1. Spring AI 启动时扫描到这个注解，生成一个 ToolDefinition：
     *      { name: "readFile",
     *        description: "Read content from a file",
     *        parameters: [{ name: "fileName", type: "string",
     *                       description: "Name of a file to read" }] }
     *   2. 把这个 ToolDefinition 传给 AI（作为 toolCallbacks 的一部分）
     *   3. AI 看到用户说"帮我读一下笔记.txt"，对照 ToolDefinition 决定调 readFile("笔记.txt")
     *
     * description 要用英文——
     *   大模型对英文指令的理解和遵循度远高于中文。
     *   这里的 description 是给 AI 看的，不是给人看的，所以用英文效果最好。
     *
     * @param fileName 要读取的文件名（AI 根据用户意图自动填入）
     * @return 文件内容字符串，如果出错则返回错误信息
     */
    @Tool(description = "Read content from a file")
    public String readFile(
            @ToolParam(description = "Name of a file to read") String fileName
    ) {
        // 拼出完整文件路径：项目根目录/tmp/file/xxx.txt
        String filePath = FILE_DIR + "/" + fileName;
        try {
            // Hutool 的 FileUtil.readUtf8String()：
            //   打开文件 → 以 UTF-8 读入全部内容 → 转成 String → 返回
            //   比自己写 FileReader + BufferedReader 省了至少 5 行代码
            return FileUtil.readUtf8String(filePath);
        } catch (Exception e) {
            // 工具方法的异常处理惯例：
            //   不抛异常！而是返回错误信息字符串。
            //   这样 AI 可以看到"读文件失败了，原因是 xxx"，
            //   然后 AI 可以尝试其他方法或告知用户。
            //   如果直接抛异常，ToolCallingManager 会捕获但信息会丢失。
            return "Error reading file: " + e.getMessage();
        }
    }

    // ========================================================================
    // 工具 2：writeFile —— 写入文件
    // ========================================================================

    /**
     * 将内容写入文件
     *
     * 和 readFile 的区别：
     *   readFile 有 1 个参数 → 只需要文件名
     *   writeFile 有 2 个参数 → 需要文件名 + 写入内容
     *
     * AI 怎么知道要传两个参数？
     *   每个参数上的 @ToolParam(description = "...") 会告诉 AI：
     *     - fileName：你要写入哪个文件？
     *     - content：你要往文件里写什么？
     *   AI 会从用户的消息里提取这两个信息：
     *     "帮我把'今天天气很好'存到日记.txt"
     *       → fileName = "日记.txt", content = "今天天气很好"
     *
     * @param fileName 要写入的文件名（AI 从用户消息中提取）
     * @param content  要写入的内容（AI 从用户消息中提取）
     * @return 成功信息或错误信息字符串
     */
    @Tool(description = "Write content to a file")
    public String writeFile(
            @ToolParam(description = "Name of the file to write") String fileName,
            @ToolParam(description = "Content to write to the file") String content
    ) {
        // 拼出完整文件路径
        String filePath = FILE_DIR + "/" + fileName;

        try {
            // 确保目录存在（如果目录不存在，先创建）
            // 比如第一次写文件时，tmp/file/ 目录可能还不存在
            FileUtil.mkdir(FILE_DIR);

            // Hutool 的 FileUtil.writeUtf8String()：
            //   以 UTF-8 编码把字符串内容写入文件
            //   如果文件已存在会被覆盖，如果不存在会新建
            FileUtil.writeUtf8String(content, filePath);

            // 返回成功信息——AI 会看到这个，知道操作成功了
            return "File written successfully to: " + filePath;
        } catch (Exception e) {
            // 写入失败（磁盘满了、没有写权限等），返回错误给 AI
            return "Error writing to file: " + e.getMessage();
        }
    }
}
