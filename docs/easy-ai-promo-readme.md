# AstraFlow

> **让任何业务系统，3 天具备 "AI 对话式业务助手" 能力。**
> 一套 Java/Spring Boot 注解驱动框架：你只需要用注解声明业务任务和字段，框架自动完成
> **意图识别 → 多轮参数收集 → 字段校验 / 标准化 → 业务执行 → 结果返回**
> 的完整闭环。



![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)



![Java 17](https://img.shields.io/badge/Java-17-blue)



![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4-green)



***

## 为什么需要它？

传统业务系统接入 AI 对话能力，通常要面对三大难题：



| 难题          | 传统做法             | AstraFlow 的做法                                     |
| ----------- | ---------------- | ----------------------------------------------- |
| **意图识别不稳定** | 手写关键词 / 正则，误判率高  | LLM 意图识别 + 关键词兜底两级，返回 `intentReason` 判断理由，提示词可调 |
| **多轮收集参数**  | 每个场景手写状态机 / 会话管理 | 注解声明字段，框架自动 "缺什么问什么"，多轮渐进收集                     |
| **字段质量**    | 靠提示词碰运气          | 校验（枚举 / 正则 / 长度 / 自定义）+ 标准化（手机号 / 日期统一格式）+ 字段映射 |

**核心思想**：把 "AI 对话能力" 从 "每次从零写" 变成 "声明式配置"，业务代码零状态机、零会话样板代码。



***

## 核心特性



| 能力            | 说明                                                        |
| ------------- | --------------------------------------------------------- |
| 🎯 **意图识别**   | LLM 优先 + 关键词降级，返回判断理由 `intentReason` 与置信度，方便调试提示词         |
| 💬 **多轮参数收集** | 缺什么问什么，支持渐进式收集、指定任务直连、任务切换                                |
| ✅ **字段校验**    | 枚举自动转换、非空 / 正则 / 长度 / 数值范围 + 自定义校验器管道                     |
| 🔀 **字段前置条件** | `@AiPremise` 表达式（`&&`/\`                                  |
| 🧹 **字段标准化**  | SPI 归一化器，手机号 / 日期等统一格式                                    |
| 🧩 **字段映射**   | 一个字段可映射多个业务目标（`@AiMapping`）                               |
| ⚡ **纯动作场景**   | 无需参数的场景（查物流、报价、转人工）只需一个 `@AiTask`，零 DTO                   |
| 🔌 **多模型**    | DeepSeek / Kimi / Doubao / Qwen / OpenAI 兼容，可配 fallback 链 |
| 🏢 **多租户隔离**  | `(tenantId, sessionId)` 双维度隔离，会话 / 历史 / 任务 / 配置全隔离        |
| 🔁 **状态持久化**  | 任务状态入数据库，重启可恢复，乐观锁防并发                                     |
| 📜 **对话历史**   | 独立消息表，滑动窗口注入上下文，支持查询 / 分页                                 |
| ⚙️ **配置热更新**  | 注解为主 + 数据库覆盖为辅，字段提取提示词发布即生效（全局 / 租户两级）                    |
| 🛡️ **工程化**   | 限流熔断、分布式锁、SSE 流式、雪花 ID、相对日期上下文变量、LLM 调用日志开关               |



***

## 3 分钟快速开始

### 1. 引入依赖



```
<dependency>
\    <groupId>com.link</groupId>
\    <artifactId>easy-ai-starter</artifactId>
\    <version>1.0.0</version>
</dependency>
```

### 2. 定义一个业务任务（2 个类）



```
// ① 任务执行器：声明"能做什么"
@AiTask(
\    value = "CREATE_TICKET",
\    name = "创建工单",
\    description = "通过多轮对话收集工单信息并创建工单",
\    triggers = {"创建工单", "我要投诉", "我要建议"}
)
public class CreateTicketTask implements TaskExecutor {
\    @Override
\    public TaskResult execute(ExecuteContext context) {
\        Map<String, Object> params = context.getParameters();
\        // 你的业务逻辑：调用 Service 创建工单
\        return TaskResult.success("工单已创建成功！工单编号：TK123456789", null);
\    }
}
// ② 参数 DTO：声明"要收集什么"
@Data
@AiTaskParam(type = "CREATE_TICKET")
public class CreateTicketParam {
\    @AiField(name = "工单类型(咨询/投诉/建议)", required = true)
\    private TicketType ticketType;          // 枚举字段自动校验
\    @AiField(name = "客户姓名", required = true)
\    private String customerName;
\    @AiField(name = "联系电话", required = true)
\    @AiValid(by = PhoneValidator.class)     // 自定义校验器
\    private String phone;
\    @AiField(name = "问题描述", required = true)
\    @AiPremise("ticketType != null")        // 前置条件：先有类型再问描述
\    private String description;
}
```

### 3. 调用一个接口



```
ChatResponse resp = aiChatService.chat(
\    "我要投诉物流太慢，我叫张三 13800138000",
\    sessionId,
\    TaskContext.builder().tenantId("001").sessionId(sessionId).build()
);
```

**效果**：



```
用户: 我要投诉
AI:   好的，请提供工单类型、客户姓名、联系电话等
用户: 我叫张三，电话 13800138000，物流太慢
AI:   工单已创建成功！工单编号：TK123456789
```

> 全流程：意图识别 → 多轮收集 → 校验 → 标准化 → 执行业务 → 返回结果，
> **全部框架自动完成**
> 。



***

## 使用场景



* 🛎️ **AI 客服 / 工单系统**：投诉、咨询、建议自动建单

* 📦 **物流 / 货代业务助手**：查轨迹、查价、索取单证文件、渠道查询

* 🏥 **预约 / 挂号系统**：多字段预约登记，日期 / 手机号自动标准化

* 💰 **报销 / 发票 / 账单**：多轮收集报销信息、申请发票、查询账单

* 📝 **请假 / 审批**：类型、时长、原因多轮收集

* 🛒 **订单管理**：改单、查单、退换货



***

## 垂直行业方案：货代 / 物流 AI 客服

框架已在**货代行业**沉淀 30+ 业务场景，覆盖：



* **查询类**：轨迹查询、查价、偏远地区查询、问题件查询、当地电话查询、收件人地址查询

* **单证索取类**：转单号 / 子转单号、转单标签 (LB / 面单)、入仓标签、转单发票、客户发票、DGD、DG 唛头、POD 签收单、提单、发票模板、客户账单、报价表、单件明细

* **业务办理类**：代下单 / 预报、修改订单、扣件 / 取消暂扣、文件上传、收货确认

* **售后类**：投诉反馈、人工客服、任务终止

支持物流行业**专有名词**（渠道名、单号格式）的字段提取规则，支持企微会话存档 + 多账号 AI 客服接入。



***

## 技术栈与兼容性



* Java 17+ / Spring Boot 3.x/ MySQL（Liquibase 自动建表）

* 多 LLM Provider：DeepSeek / Kimi / Doubao / Qwen / OpenAI 兼容接口

* 注解驱动、零 XML、开箱即用



***

## License

MIT License — 可自由商用，保留版权声明即可。

## 联系与合作



* 企业版 / 私有化部署 / 行业定制 / 技术支持，欢迎联系：[请填入联系方式]