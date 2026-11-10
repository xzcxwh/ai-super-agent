// ============================================================================
// index.js —— 前端与后端的"电话线"
// ============================================================================
// 通俗理解：
//   这个文件就像一部电话，封装了"拨号"的细节。
//   页面（Vue 组件）想跟后端说话时，不需要知道电话号码、不需要知道怎么拨号，
//   只需要调用 export 出来的函数，比如 chatWithLoveApp("你好", "user001") 就行。
//
// 本文件做了 3 件事：
//   ① 配置基础地址（API_BASE_URL）  → 告诉"电话"要打给谁
//   ② 封装 SSE 连接（connectSSE）   → 打通一条"热线电话"，后端可以持续传话过来
//   ③ 提供两个对话函数              → 给页面直接调用的按钮
// ============================================================================


// ============================================================================
// 第一步：引入 axios（HTTP 请求库）
// ============================================================================

// axios 是什么？
//   前端用来发 HTTP 请求的库，比浏览器自带的 fetch 更好用。
//   你可以理解成"微信"：用它才能跟后端"聊天"。
//   但我们这个项目主要用 SSE（Server-Sent Events，服务端推送），
//   所以实际只在创建 axios 实例时用它设了超时时间，
//   真正的对话是通过 EventSource（浏览器内置的 SSE 客户端）来实现的。
import axios from 'axios'


// ============================================================================
// 第二步：根据环境自动切换后端地址
// ============================================================================

// process.env.NODE_ENV 是 Node.js 的环境变量，表示当前运行模式：
//   'development' → 开发模式（npm run dev 时），访问本地后端
//   'production'  → 生产模式（npm run build 后部署），访问线上后端
//
// 三元表达式：条件 ? 真的时候用这个 : 假的时候用这个
//   相当于一个超级简短的 if-else：
//     if (是生产环境) 用 '/api'       ← 前后端部署在一起，用相对路径
//     else           用 'http://localhost:8123/api'  ← 开发时前后端分开跑
// 判断是不是生产环境 → 选择对应地址
const API_BASE_URL = process.env.NODE_ENV === 'production'
    ? '/api'                                // 生产环境 → 相对路径（前后端部署在同一域名下）
    : 'http://localhost:8123/api';          // 开发环境 → 指向本机 8123 端口的后端


// ============================================================================
// 第三步：创建 axios 实例（设置全局超时时间）
// ============================================================================

// axios.create({...}) 创建一个"配置好的 axios 实例"，像一个定制版的微信。
// baseURL：所有请求的地址前缀，后面拼上具体路径。
// timeout：超时时间，60000 毫秒 = 60 秒。超过 60 秒没回复就算失败。
// 创建 axios 实例（超时 60 秒）
const request = axios.create({
    baseURL: API_BASE_URL,                   // 基础地址（开发→localhost:8123/api，生产→/api）
    timeout: 60000                           // 超时时间 60 秒（60 秒没回复就算失败）
})


// ============================================================================
// 第四步：封装 SSE 连接（Server-Sent Events，服务端推送）
// ============================================================================

// SSE 是什么？
//   普通的 HTTP 请求：前端问一句，后端答一句，然后就挂了。
//   SSE：前端打一个电话过去，电话一直不挂，后端有新消息就"喂喂喂"实时传过来。
//
//   为什么要用 SSE？
//   因为 AI 回复是"一个字一个字"生成的，如果用普通 HTTP，用户要等 10 秒才能看到完整回复。
//   用 SSE，用户能看到文字一个字一个字地"流"出来，体验好很多。
//
//   浏览器内置的 EventSource 对象就是用来接收 SSE 的，
//   它只能发 GET 请求，参数只能放在 URL 里。

// 导出 connectSSE 函数：让外面的 Vue 组件可以 import 使用
// 参数说明：
//   url       → 后端接口路径，比如 '/ai/love_app/chat/sse'
//   params    → 要传给后端的参数对象，比如 { message: '你好', chatId: 'user001' }
//   onMessage → 收到消息时的回调函数（每收到一条就执行一次）
//   onError   → 连接出错时的回调函数
export const connectSSE = (url, params, onMessage, onError) => {

    // --- 把参数对象拼成 URL 查询字符串 ---
    const queryString = Object.keys(params)      // 取出参数的所有 key → ['message', 'chatId']
        .map(key =>
            `${encodeURIComponent(key)}=${encodeURIComponent(params[key])}`  // key=编码后的value
        )
        .join('&');                              // 用 & 串成: message=xxx&chatId=xxx

    // 拼接完整 URL
    const fullUrl = `${API_BASE_URL}${url}?${queryString}`;

    // --- 创建 EventSource（浏览器的 SSE 客户端） ---
    // 自动发起 GET 请求，保持连接打开，等待服务器持续推送数据
    const eventSource = new EventSource(fullUrl);

    // --- 监听消息 ---
    // 服务器每推送一条数据，这个回调就触发一次
    eventSource.onmessage = event => {
        let data = event.data;                   // 取出实际数据

        // [DONE] 是约定的结束信号，表示"AI 说完了"
        if (data === '[DONE]') {
            if (onMessage) onMessage('[DONE]');  // 通知页面：对话结束
            eventSource.close();                 // 主动关闭连接，防止自动重连
        } else {
            if (onMessage) onMessage(data);      // 通知页面：显示这段文字
        }
    };

    // --- 监听错误 ---
    // 连接断开、网络超时、后端崩溃时触发
    eventSource.onerror = error => {
        if (onError) onError(error);             // 通知页面：出错了
        eventSource.close();                     // 关闭连接（否则会自动重试）
    };

    // 返回 EventSource 实例，让调用者可以手动 .close() 断开连接
    return eventSource;
};


// ============================================================================
// 第五步：提供两个"傻瓜式"的对话函数（给 Vue 页面直接调用）
// ============================================================================

// --- 5-1. AI 恋爱大师聊天 ---
// 页面调用方式：
//   import { chatWithLoveApp } from '@/api/index.js'
//   const es = chatWithLoveApp('你好', 'user001')
//
// 这个函数底层就是调了 connectSSE，只不过把参数帮你填好了，
// 页面不用关心 URL 怎么写、参数怎么拼。
//
// 参数：
//   message → 用户输入的文字
//   chatId  → 会话 ID（同一个 ID 可以让 AI 记住之前的对话内容）
export const chatWithLoveApp = (message, chatId) => {
    // 实际请求：GET http://localhost:8123/api/ai/love_app/chat/sse?message=xxx&chatId=xxx
    return connectSSE('/ai/love_app/chat/sse', { message, chatId });
};

// --- 5-2. AI 超级智能体聊天 ---
// 和上面类似，但不需要 chatId（超级智能体的记忆在服务端实例里）。
// 实际请求：GET http://localhost:8123/api/ai/manus/chat?message=xxx
export const chatWithManus = (message) => {
    // 实际请求：GET http://localhost:8123/api/ai/manus/chat?message=xxx
    return connectSSE('/ai/manus/chat', { message });
};

// --- 5-3. AI 代码助手聊天（实验 2：你自己的第二个 Agent） ---
// 和 chatWithManus 完全一样，只是 URL 不同
export const chatWithCodeAssistant = (message) => {
    // 实际请求：GET http://localhost:8123/api/ai/code_assistant/chat?message=xxx
    return connectSSE('/ai/code_assistant/chat', { message });
};


// ============================================================================
// 第六步：默认导出（方便用 import api from '@/api' 的方式调用）
// ============================================================================
// export default 和上面 export const 的区别：
//   export const chatWithLoveApp  → 用时：import { chatWithLoveApp } from '...'（花括号解构）
//   export default { ... }        → 用时：import api from '...'; api.chatWithLoveApp(...)
//
// 两种方式都可以，看个人习惯，本项目好像没用这个默认导出，但不影响。
export default {
    chatWithLoveApp,
    chatWithManus,
    chatWithCodeAssistant
};
