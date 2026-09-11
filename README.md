# baguwen-rag — MianCoach「AI 模拟面试官」(RAG)

MianCoach 是一个基于 RAG 的技术知识学习应用,支持知识库问答、来源引用、模拟面试批改与练习复盘。题库语料来自小林coding,使用时保留作者署名与原文链接。
技术:Spring Boot 3 / **Java 17**+ Vue3(Vite) + PostgreSQL16/pgvector + DashScope(OpenAI 兼容协议)。语料抓取用 Python。

## 项目结构

```
docs/requirements.md  需求与接口文档
docs/run.md           启动手册(三步起)
compose.yml           pgvector 容器(baguwen-pg @15432,命名带前缀防撞车)
crawler/              Python:HTML→md(已完成:4板块13页574题语料)
corpus/               语料层:corpus/interview/<板块>/<页>.md
server/               Spring Boot 后端(jar 31MB 已可跑)
web/                  Vue3 前端(四页)
```

## 现状(2026-09-12,feat/production-ready 分支):全链路可用 + 检索管线升级

- **索引**:corpus → Java 分块(叶子级标题=题,兼容 h2/h3)→ **长答案切子段(parent-child,574 题 → 1155 子段)**
  → 向量(1024d)→ pgvector;`index_meta` 指纹带分块方案,换方案未重灌直接 409
  - `POST /api/admin/reindex` = **13 doc / 574 chunk / 1155 seg**
- **检索**:exact / vector(题目级+子段级同池) / keyword 多路召回 → 前 20 候选交 **bge-reranker-v2-m3(免费)统一重排**
  → top5;子段命中的重排证据用子段正文;重排挂掉自动降级回召回原序
- **拒答**:余弦与重排分双门槛(同时过低才拒),域外问题不硬答,`noAnswer` 事件 + 诚实话术
- **评测(去自环)**:`POST /api/admin/eval/build` 用 LLM 把题干改写成真实问法 + 细节探针 + 域外负样本;
  `GET /api/admin/eval?mode=golden` 报 recall@k / MRR / 延迟 / 拒答定标。数字见 **docs/eval-report.md**:
  探针(细节题)recall@1 0.615→**0.795**、recall@5 0.846→**0.949**
- **多轮追问**:`/api/chat/ask` 带 history,追问里的"它/那个"先改写成独立问题再检索
- **问答**:`POST /api/chat/ask`(SSE:refs→delta→done),子段命中片段前置进上下文,答案引用可点开原文
- **模拟面试**:`/api/quiz/start|pick|grade`,批改=LLM 对照标准原文逐点评分(hits/missing),落库
- **复盘**:场次记录 + 按板块/类目的薄弱聚合
- 前端四页(问答/知识库/模拟面试/复盘),deep-first 设计(代码绿 x 深夜自习,ui-ux-pro-max 定调),深浅双主题
- Swagger: http://localhost:8080/swagger-ui.html

运行方式见 `docs/run.md`。**默认 auto 模式(没 key 自动走 mock,免配置联调);使用真实模型请配 `BAGUWEN_CHAT_API_KEY` + `BAGUWEN_EMBED_API_KEY`。**

## 语料(corpus/)状态与约定

- 布局:corpus/interview/<板块group>/<页>.md;板块按**站侧边栏编号**分组(06架构设计=消息队列+分布式+系统设计)。
- 两种题级结构:java/database/mq/cap 页 h2=类目 h3=题;systemdesign 与 tools 页 h2 直接当题。切题规则=**叶子级标题即一道题**。
- **AI-Agent 面试篇在本站无正文**(5 板块全指向外站 xiaolinnote.com 导流页),未抓、待定。
- 语料页 13: java5 + database2(mysql94/redis48) + architecture3(mq38/cap19/systemdesign14) + tools3(linux/git/docker)。

## 抓取(已稳定)

```bash
.venv/Scripts/python -m crawler.main            # 抓 PAGES 清单全部
.venv/Scripts/python crawler/probe_groups.py    # 摸底站点板块结构
```
已处理:标题锚点、代码行号、页顶广告、嵌套 strong、全角标点保真、图片只留外链引用。
