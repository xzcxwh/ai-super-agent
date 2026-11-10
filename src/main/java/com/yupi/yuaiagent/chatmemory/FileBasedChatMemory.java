package com.yupi.yuaiagent.chatmemory;

// Kryo：高性能 Java 二进制序列化库（比 Java 自带的 Serializable 快 10 倍以上）
import com.esotericsoftware.kryo.Kryo;
// Kryo 的输入流：从文件读二进制数据
import com.esotericsoftware.kryo.io.Input;
// Kryo 的输出流：往文件写二进制数据
import com.esotericsoftware.kryo.io.Output;
// Objenesis 的实例化策略：让 Kryo 能序列化没有无参构造函数的对象
import org.objenesis.strategy.StdInstantiatorStrategy;
// ChatMemory 接口：Spring AI 定义的"对话记忆"标准接口
import org.springframework.ai.chat.memory.ChatMemory;
// Spring AI 的消息接口：对话中的每条消息（UserMessage、AssistantMessage 等）
import org.springframework.ai.chat.messages.Message;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * FileBasedChatMemory（基于文件持久化的对话记忆）—— AI 的"笔记本"
 *
 * 通俗理解：
 *   正常 AI 对话是"鱼的记忆"——每次请求都是独立的，记不住上一轮聊了什么。
 *   ChatMemory 就是给 AI 配了一个"笔记本"——每次对话完记下来，下次对话前翻出来看。
 *
 * 和 InMemoryChatMemoryRepository 的区别：
 *   InMemory  → 记在脑子里（内存），重启项目就忘光了
 *   本类      → 记在本子上（磁盘文件），重启项目还能想起来
 *
 *   你的项目目前用的是 InMemory（因为 maxMessages=20 的滑动窗口足够了），
 *   但本类展示了"怎么把记忆存到磁盘"的实现方式，作为备选方案。
 *
 * ChatMemory 接口定义了 3 个方法：
 *   add(conversationId, messages)   → 往某个会话里追加消息
 *   get(conversationId)             → 获取某个会话的所有消息
 *   clear(conversationId)           → 清空某个会话的消息
 *
 * conversationId 是什么？
 *   就像微信群聊的名字。不同的 conversationId 对应不同的对话。
 *   比如 user001_chat001 和 user001_chat002 是两个独立的会话，
 *   它们的记忆互不干扰。
 *
 * 文件存储方式：
 *   BASE_DIR/会话ID.kryo
 *   比如 tmp/chat-memory/user001_chat001.kryo
 *   .kryo 是 Kryo 序列化后的二进制文件（人读不了，但程序读起来飞快）
 *
 * 为什么用 Kryo 而不是 JSON？
 *   - Kryo 是二进制格式，比 JSON 快得多
 *   - Message 对象（UserMessage、AssistantMessage 等）结构复杂，
 *     用 JSON 需要手动写序列化/反序列化代码
 *   - Kryo 能自动处理对象图（对象里有对象、List 里有对象）
 */
public class FileBasedChatMemory implements ChatMemory {  // ← 实现 Spring AI 的 ChatMemory 接口

    /**
     * 记忆文件存储的根目录
     * 比如：C:\PycharmProject\MAIN\tmp\chat-memory\
     * final = 构造函数设置后不可变
     */
    private final String BASE_DIR;

    /**
     * Kryo 序列化器实例
     *
     * 为什么用 static final？
     *   Kryo 实例创建成本高，且不是线程安全的。
     *   但本项目是单线程场景（每次请求独立处理），所以共享一个实例没问题。
     *   如果是多线程高并发场景，应该用 ThreadLocal 或对象池。
     */
    private static final Kryo kryo = new Kryo();

    /**
     * static 代码块：类加载时执行一次，配置 Kryo
     *
     * 类加载时机：JVM 第一次碰到 FileBasedChatMemory 这个类名时执行。
     * 只执行一次，且在最前面（先于构造函数）。
     */
    static {
        // 允许序列化没有注册的类（不用每个类都预先 register）
        // 如果设为 true，序列化前必须逐个 kryo.register(Xxx.class)，很麻烦
        kryo.setRegistrationRequired(false);

        // 设置实例化策略：让 Kryo 能创建没有无参构造函数的对象
        // StdInstantiatorStrategy 使用 Objenesis 库绕过构造函数直接创建对象
        // 这对 Spring AI 的 Message 类型很重要（它们可能没有无参构造函数）
        kryo.setInstantiatorStrategy(new StdInstantiatorStrategy());
    }

    /**
     * 构造函数：指定记忆文件的存储目录
     *
     * 调用时机：
     *   在 LoveApp 的构造函数里有注释掉的用法：
     *     String fileDir = System.getProperty("user.dir") + "/tmp/chat-memory";
     *     ChatMemory chatMemory = new FileBasedChatMemory(fileDir);
     *
     * 构造函数做的事：如果目录不存在，自动创建。
     *
     * @param dir 文件保存目录的路径
     */
    public FileBasedChatMemory(String dir) {
        this.BASE_DIR = dir;
        File baseDir = new File(dir);
        // 如果目录不存在，创建（包括所有父目录）
        if (!baseDir.exists()) {
            baseDir.mkdirs();                                // mkdirs 和 mkdir 区别：前者会创建所有不存在的父目录
        }
    }

    // ========================================================================
    // ChatMemory 接口实现：add() + get() + clear()
    // ========================================================================

    /**
     * 往指定会话追加消息
     *
     * 工作流程：
     *   ① 从磁盘文件读取这个会话的现有消息
     *   ② 把新消息追加到末尾
     *   ③ 把合并后的消息列表写回磁盘
     *
     * 比如：
     *   用户发"你好" → messages = [UserMessage("你好"), AssistantMessage("你好！")]
     *   add("chat001", messages) → chat001.kryo 文件内容变为这 2 条消息
     *
     * @param conversationId 会话 ID（区分不同对话）
     * @param messages       要追加的新消息列表
     */
    @Override
    public void add(String conversationId, List<Message> messages) {
        // ① 从磁盘读出这个会话已有的消息
        List<Message> conversationMessages = getOrCreateConversation(conversationId);
        // ② 把新消息追加到末尾
        conversationMessages.addAll(messages);
        // ③ 写回磁盘
        saveConversation(conversationId, conversationMessages);
    }

    /**
     * 获取指定会话的所有历史消息
     *
     * MessageChatMemoryAdvisor 在每次 AI 调用前会调这个方法，
     * 拿到历史消息后注入到 Prompt 里，让 AI "想起来"之前聊了什么。
     *
     * @param conversationId 会话 ID
     * @return 这个会话的所有历史消息（新会话返回空列表）
     */
    @Override
    public List<Message> get(String conversationId) {
        return getOrCreateConversation(conversationId);
    }

    /**
     * 清空指定会话的所有消息
     *
     * 实现方式：直接删除这个会话对应的 .kryo 文件。
     * 下次 get() 时会因为文件不存在而返回空列表。
     *
     * @param conversationId 会话 ID
     */
    @Override
    public void clear(String conversationId) {
        File file = getConversationFile(conversationId);
        if (file.exists()) {
            file.delete();                                   // 直接删文件
        }
    }

    // ========================================================================
    // 私有辅助方法
    // ========================================================================

    /**
     * 获取或创建会话的消息列表
     *
     * 如果文件存在 → 用 Kryo 从文件反序列化出消息列表
     * 如果文件不存在 → 返回空列表（新会话，还没有历史消息）
     *
     * 方法名里的 "OrCreate" 表明这是一个"懒加载"模式：
     *   不提前创建文件，等到第一次需要时才读/建。
     *
     * @param conversationId 会话 ID
     * @return 消息列表（可能是空的）
     */
    private List<Message> getOrCreateConversation(String conversationId) {
        File file = getConversationFile(conversationId);     // 拼接文件路径
        List<Message> messages = new ArrayList<>();

        if (file.exists()) {
            // 文件存在 → 反序列化读出
            // try-with-resources 语法：括号里的 Input 在用完后自动关闭（close）
            try (Input input = new Input(new FileInputStream(file))) {
                // kryo.readObject(input, ArrayList.class)：
                //   从 input 流里读二进制数据 → 反序列化成 ArrayList 对象
                messages = kryo.readObject(input, ArrayList.class);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        // 文件不存在 → 返回空的 ArrayList，代表新会话
        return messages;
    }

    /**
     * 保存会话的消息列表到磁盘
     *
     * 用 Kryo 把整个 messages 列表序列化成二进制，写入 .kryo 文件。
     * 如果文件已存在会被覆盖（不是追加）。
     *
     * @param conversationId 会话 ID
     * @param messages       要保存的消息列表
     */
    private void saveConversation(String conversationId, List<Message> messages) {
        File file = getConversationFile(conversationId);     // 拼接文件路径

        // try-with-resources：Output 用完后自动关闭
        try (Output output = new Output(new FileOutputStream(file))) {
            // kryo.writeObject(output, messages)：
            //   把 messages 对象序列化成二进制，写入 output 流 → 写入文件
            kryo.writeObject(output, messages);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 根据会话 ID 拼接文件路径
     *
     * 规则：BASE_DIR/会话ID.kryo
     * 比如：tmp/chat-memory/user001_chat001.kryo
     *
     * 为什么用 .kryo 扩展名？
     *   只是约定，方便调试时在文件管理器里识别。
     *   改成 .bin 或 .dat 效果一样。
     *
     * @param conversationId 会话 ID
     * @return 对应的文件对象
     */
    private File getConversationFile(String conversationId) {
        return new File(BASE_DIR, conversationId + ".kryo");
    }
}
