# 内置浏览器

内置浏览器是 ActMe 的联网信息获取能力。它让 Agent 可以搜索网页、打开指定 URL，并读取渲染后的页面文本。

## 目标

内置浏览器解决两个问题：

1. 大模型知识可能过期，必须联网确认。
2. 普通 HTTP 抓取经常读不到动态页面，需要真实浏览器渲染。

因此 ActMe 使用 GeckoView 作为内置浏览器基础，并在 Agent 工具层提供：

- `web_search`
- `browse_url`
- `browser_url`
- `web_browse`
- `open_url`

其中 `browse_url` / `browser_url` 会打开网页并返回渲染后的文本。

## 主要实现

核心代码位置：

```text
app/src/main/java/com/actme/app/data/agent/SystemSkillExecutor.kt
app/src/main/java/com/actme/app/data/agent/GeckoSearchEngine.kt
app/src/main/java/com/actme/app/ui/GeckoDebugActivity.kt
```

实现分层：

- `SystemSkillExecutor`：接收 Agent 的 system call，决定调用搜索还是网页浏览。
- `GeckoSearchEngine`：管理 GeckoView runtime/session，执行搜索或网页渲染读取。
- `GeckoDebugActivity`：设置页里的“内置浏览器”调试入口，用于人工验证页面加载和搜索效果。

## Agent 调用示例

搜索：

```json
{
  "type": "web_search",
  "query": "2026年5月31日国际金价实时价格"
}
```

打开网页：

```json
{
  "type": "browse_url",
  "url": "https://www.boc.cn/fimarkets/"
}
```

## 搜索策略

当前搜索后端由 `SystemSkillExecutor` 管理。优先级包含：

1. Bing Gecko
2. Bing HTML
3. DuckDuckGo API
4. DuckDuckGo HTML
5. Baidu
6. SearXNG

实际使用中，ActMe 倾向于让 Agent 多搜索、多浏览来源进行确认，尤其是：

- 当前信息。
- 金融价格。
- 官网公告。
- 政策法规。
- 产品参数。
- 需要高准确性的事实。

## 页面读取

浏览网页时，App 会尽量返回渲染后的页面文本。Agent 可据此：

- 判断页面是否权威。
- 提取正文。
- 继续打开页面中的相关链接。
- 对多个来源做交叉验证。

如果搜索结果只提供面包屑式 URL，例如：

```text
https://www.boc.cn › fimarkets
```

Agent 可以还原为：

```text
https://www.boc.cn/fimarkets
```

再调用 `browse_url`。

## UI 与调试

设置页提供“内置浏览器”入口，打开后可以：

- 手动输入 URL。
- 查看 GeckoView 页面加载。
- 验证搜索 URL。
- 查看 logcat 中的浏览器行为。

这个入口主要用于调试，不是最终用户主流程。

## 限制

- 某些页面需要登录或验证码，内置浏览器不一定能自动读取。
- 页面文本提取可能包含导航、广告、脚本生成文本。
- Bing 等搜索结果在不同网络、地区、Cookie 状态下可能变化。
- 动态页面即使渲染成功，也可能需要额外交互才能看到数据。

## 与其他能力配合

内置浏览器通常和 Python 配合使用：

1. `web_search` 找来源。
2. `browse_url` 读取页面。
3. `python_exec` 清洗、排序、去重、计算。
4. Agent 总结结论并引用来源文本。
