# 让业务系统 "会聊天办事"：我用注解写了一套 AI 业务助手框架

> 导读：给业务系统接 AI 对话能力，最痛苦的不是调大模型，而是意图识别、多轮收集参数、字段校验这些 "AI 之外" 的脏活。这篇文章分享一套注解驱动的思路 —— 你只需要声明 "任务要什么字段"，剩下的框架全包了。



***

## 一、先说痛点：业务系统接 AI，坑在哪

假设老板让你给公司的客服系统接上 AI，客户说一句话就能自动建工单、查物流、申请退款。听起来很美好，真正动手你会遇到三个坑：

**坑 1：意图识别不稳定**

"我要投诉" 和 "我投诉的内容是物流太慢"，前者是意图，后者是在描述问题。关键词匹配会翻车，正则写到最后自己都看不懂。要让 LLM 做意图判断，又得每次调 prompt、调阈值，还看不出它为什么判错。

**坑 2：多轮收集参数绕**

建一个工单要 5 个字段，客户第一次只说了一个。你得维护状态机：现在问到第几个字段了？上一个字段值存哪？客户中途换了话题怎么办？这一套每个场景写一遍，代码量直接爆炸。

**坑 3：字段质量不可控**

让 LLM 提取 "手机号"，它能给你提出 "138 0013 8000""(86) 13800138000""138-0013-8000" 各种格式，后面接业务系统全崩。校验、格式化、枚举映射，又是一堆样板代码。

> 说白了：
> **90% 的工作量花在了 AI 之外的工程问题上。**



***

## 二、解题思路：把 "AI 对话能力" 变成声明式配置

我的核心想法很简单：**业务系统接入 AI 对话，不该是 "每次从零写一套"，而应该是 "用注解声明，框架自动编排"。**

于是有了 **AstraFlow** —— 一套 Java/Spring Boot 注解驱动框架：



* 你用 `@AiTask` 声明 "这个业务能做什么"

* 你用 `@AiTaskParam` + `@AiField` 声明 "要收集哪些字段"

* 框架自动完成：**意图识别 → 多轮参数收集 → 校验 → 标准化 → 执行业务 → 返回结果**

业务代码里，**没有任何状态机、没有任何会话样板代码**。



***

## 三、跑起来：一个创建工单的例子

先定义一个任务执行器，告诉 AI"我们能创建工单"：



```
@AiTask(
    value = "CREATE_TICKET",
    name = "创建工单",
    description = "通过多轮对话收集工单信息并创建工单",
    triggers = {"创建工单", "我要投诉", "我要建议"}
)
public class CreateTicketTask implements TaskExecutor {
    @Override
    public TaskResult execute(ExecuteContext context) {
        Map<String, Object> params = context.getParameters();
        // 你的业务逻辑：调用 Service 创建工单
        return TaskResult.success("工单已创建成功！工单编号：TK123456789", null);
    }
}
```

再定义一个参数 DTO，声明要收集哪些字段：



```
@Data
@AiTaskParam(type = "CREATE_TICKET")
public class CreateTicketParam {
    @AiField(name = "工单类型(咨询/投诉/建议)", required = true)
    private TicketType ticketType;          // 枚举字段自动校验
    @AiField(name = "客户姓名", required = true)
    private String customerName;
    @AiField(name = "联系电话", required = true)
    @AiValid(by = PhoneValidator.class)     // 自定义校验器
    private String phone;
    @AiField(name = "问题描述", required = true)
    @AiPremise("ticketType != null")        // 先有类型，再问描述
    private String description;
}
```

然后调用一个接口：



```
ChatResponse resp = aiChatService.chat(
    "我要投诉物流太慢，我叫张三 13800138000",
    sessionId,
    TaskContext.builder().tenantId("001").sessionId(sessionId).build()
);
```

对话效果长这样：



```
用户: 我要投诉
AI:   好的，请提供工单类型、客户姓名、联系电话等
用户: 我叫张三，电话 13800138000，物流太慢
AI:   工单已创建成功！工单编号：TK123456789
```

**注意：整个多轮收集、意图判断、字段校验的过程，我们一行业务代码都没写。**



***

## 四、进阶：几个让我觉得 "值得" 的设计

### 1. 意图识别返回 "判断理由"，不再靠猜

每次意图识别都会返回 `intentReason`（为什么命中这个任务）和置信度：



```
resp.getIntentReason();      // 如："用户明确表达'创建工单'，与CREATE_TICKET意图直接匹配"
resp.getIntentConfidence();  // 0.0-1.0
resp.getIntentSource();      // LLM / KEYWORD / FALLBACK / CONTINUE
```

识别错了？看 `intentReason` 就知道 LLM 是**基于什么理由**判错的，改 `triggers` 或调 prompt 就能解决。这比黑盒调参舒服太多。

### 2. 字段前置条件：用一个表达式控制 "什么时候问"

有些字段要等前面的字段收集完才该问，用 `@AiPremise` 一个表达式搞定：



```
// 只要姓名或渠道其中一个存在即可
@AiPremise("customerName != null || channelName != null")
// 组合逻辑
@AiPremise("customerName != null && (phone != null || email != null)")
// 枚举包含
@AiPremise("priority in ('高','中')")
```

### 3. 字段标准化：让 LLM 的 "花式输出" 变规范

手机号、日期这种字段，用户怎么说都有。配置一个 `normalize` 就统一了：



```
@AiField(name = "联系电话", required = true, normalize = "PHONE")
private String phone;
@AiField(name = "预约日期", required = true, normalize = "DATE")
private String appointmentDate;
```

甚至能处理相对日期 —— 内置 `currentDate` 上下文变量，LLM 会自动把 "明天"" 下周一 " 换算成真实日期：



```
@AiExtract(
    description = "预约日期",
    rules = {"如果用户使用相对日期，应根据当前日期计算实际日期"},
    contextVars = {"currentDate"}
)
private String appointmentDate;
```

### 4. 纯动作场景：不需要参数，一个注解搞定

比如 "查物流"" 转人工 " 这种不用收集参数的操作，连 DTO 都不用写：



```
@AiTask(
    value = "LOGISTICS_QUERY",
    name = "物流查询",
    description = "查询订单物流状态和配送进度",
    triggers = {"物流", "查物流", "快递到哪了"}
)
public class LogisticsQueryTask implements TaskExecutor {
    @Override
    public TaskResult execute(ExecuteContext context) {
        return TaskResult.success("当前物流状态：已发货，预计明天送达", null);
    }
}
```

### 5. 提示词热更新：改字段规则不用改代码

业务规则总会变。框架支持注解为主 + 数据库覆盖为辅，发布一个 JSON 就生效：



```
{
  "taskType": "CREATE_TICKET",
  "tenantId": "T-ISO",
  "fields": {
    "phone": {
      "rules": ["租户规则：ISO手机号校验"]
    }
  }
}
```

租户级覆盖 > 全局覆盖 > 注解默认，**发布后下一次对话立即生效，不用重启**。

### 6. 多租户隔离 + 对话历史

`(tenantId, sessionId)` 双维度隔离，同一个 sessionId 在不同租户下是完全独立的会话，适合 SaaS 多客户场景。对话历史独立落表，可查询、可分页、可复盘。



***

## 五、适合什么场景

这套思路最值钱的场景是**业务表单多、对话流程固定的系统**：



* 🛎️ AI 客服 / 工单系统（投诉、咨询、建议自动建单）

* 📦 物流 / 货代业务助手（查轨迹、查价、索取单证文件）

* 🏥 预约 / 挂号系统（多字段登记，日期 / 手机号自动标准化）

* 💰 报销 / 发票 / 账单（多轮收集 + 校验）

* 📝 请假 / 审批 / 订单管理

我这边已经在一套**货代行业**场景上跑通 30+ 个业务场景（代下单、转单号、面单、发票、POD、DGD、提单、查价、偏远查询……），配合企业微信会话存档 + 多账号接入，能做到全自动 AI 客服。



***

## 六、后记

这套框架的核心取舍是：**把 "AI 对话能力" 从 "每次从零写" 变成 "声明式配置"**，用注解换取工程上的确定性 —— 意图识别可调、字段质量可控、多租户可隔离、提示词可热更新。

技术栈：Java 17 / Spring Boot 3 / MySQL，支持 DeepSeek / Kimi / Doubao / Qwen / OpenAI 兼容接口，可配 fallback 链。

如果你也受够了给业务系统接 AI 的样板代码，欢迎交流。觉得思路有启发的话，**点个赞 / 关注**，后续我会继续分享多轮对话状态管理、企微 AI 客服落地这些实战细节。