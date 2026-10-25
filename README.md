# baguwen-rag — MianCoach「AI 模拟面试官」(RAG)

MianCoach 是一个基于 RAG 的技术知识学习应用,支持知识库问答、来源引用、模拟面试批改与练习复盘。题库语料来自小林coding,使用时保留作者署名与原文链接。
技术:Spring Boot 3 / **Java 17**+ Vue3(Vite) + PostgreSQL16/pgvector + DashScope(OpenAI 兼容协议)。语料抓取用 Python。

## 项目结构

```
compose.yml           pgvector 容器(baguwen-pg @15432,命名带前缀防撞车)
crawler/              Python:HTML→md 爬虫
corpus/               语料层(不入库,运行爬虫生成):corpus/interview/<板块>/<页>.md
server/               Spring Boot 后端
web/                  Vue3 前端(问答/知识库/模拟面试/复盘)
```

## 快速启动

前置:Docker、JDK 17、Maven 3.9、Node 22。以下命令都在项目根目录执行。

**1. 生成语料**(`corpus/` 是第三方原文,版权归原作者,不随仓库分发,需要自己抓):

```bash
python -m venv .venv
.venv/Scripts/pip install requests beautifulsoup4    # Linux/macOS 用 .venv/bin/pip
.venv/Scripts/python -m crawler.main                 # 小林coding 13 页 574 题(java/database/architecture/tools)
.venv/Scripts/python -m crawler.ai_pages             # 小林面试笔记 AI 面试题 98 题(Agent/RAG/工具调用/大模型工程)
```

**2. 起数据库**

```bash
docker compose up -d            # 容器 baguwen-pg,端口 15432;表结构由后端 Flyway 自动建
```

**3. 配置并起后端**

```bash
cp .env.example .env            # 不填任何 key 也能跑:provider=auto 时自动走 mock
cd server && mvn spring-boot:run
curl -X POST http://localhost:8080/api/admin/reindex   # 全量入库:切题 → 子段 → 向量
curl http://localhost:8080/api/meta                    # 确认 chatReal/embedReal 为 true、index.compatible 为 true
```

三套模型配置**各自独立**(各有 base-url / key / model),在 `.env` 里填:

| 端 | 干什么 | 换了会怎样 |
|---|---|---|
| chat 生成端 | 问答、批改、场次报告、追问改写 | 随便换,不影响已入库的向量 |
| embed 向量端 | 建索引 + 查询向量化 | **换模型/维度必须重新 reindex**,否则检索直接 409 |
| rerank 重排端 | cross-encoder 给候选统一打分 | 换了不用重灌;挂了自动降级回召回原序 |

实测可用组合:生成走百炼(`qwen-plus`),向量 + 重排走硅基流动(`BAAI/bge-m3` + `BAAI/bge-reranker-v2-m3`,都免费)。
`BAGUWEN_EMBED_BASE_URL` 一定要显式填(`.env.example` 已填好),yml 里的默认 base-url 与默认模型不配套。
发验证码邮件填 `BAGUWEN_SMTP_PASSWORD`(默认走 Resend SMTP),不填则验证码打印在 `logs/server.log`(搜 `[mock mail]`)。

**4. 起前端**

```bash
cd web && npm install && npm run dev     # http://localhost:5173,/api 已代理到 8080
```

Swagger:http://localhost:8080/swagger-ui.html  ·  接口清单:`server/api.http`

**小坑**

- 语料新增/修改后要 `POST /api/admin/reindex` 才进检索(先清后插,题目 id 会全部重新生成)
- 改了 `SubSegmenter` 的切分规则也必须重灌:指纹里记了分块方案,不一致同样 409
- 重排会静默降级:日志里搜 `重排失败,降级回召回原序`,有就是没在跑
- Git Bash 里 curl 传中文 body 会变 GBK:用 `--data-binary @file.json`(UTF-8 文件)

## 现状(2026-09-12,feat/production-ready 分支):全链路可用 + 检索管线升级

- **索引**:corpus → Java 分块(叶子级标题=题,兼容 h2/h3)→ **长答案切子段(parent-child,574 题 → 1155 子段)**
  → 向量(1024d)→ pgvector;`index_meta` 指纹带分块方案,换方案未重灌直接 409
  - `POST /api/admin/reindex` = **13 doc / 574 chunk / 1155 seg**
- **检索**:exact / vector(题目级+子段级同池) / keyword 多路召回 → 前 20 候选交 **bge-reranker-v2-m3(免费)统一重排**
  → top5;子段命中的重排证据用子段正文;重排挂掉自动降级回召回原序
- **拒答**:余弦与重排分双门槛(同时过低才拒),域外问题不硬答,`noAnswer` 事件 + 诚实话术
- **评测(去自环)**:`POST /api/admin/eval/build` 用 LLM 把题干改写成真实问法 + 细节探针 + 域外负样本;
  `GET /api/admin/eval?mode=golden` 报 recall@k / MRR / 延迟 / 拒答定标(数字见下方「评测结果」)
- **多轮追问**:`/api/chat/ask` 带 history,追问里的"它/那个"先改写成独立问题再检索
- **问答**:`POST /api/chat/ask`(SSE:refs→delta→done),子段命中片段前置进上下文,答案引用可点开原文
- **模拟面试**:`/api/quiz/start|pick|grade`,批改=LLM 对照标准原文逐点评分(hits/missing),落库
- **复盘**:场次记录 + 按板块/类目的薄弱聚合
- 前端四页(问答/知识库/模拟面试/复盘),deep-first 设计(代码绿 x 深夜自习,ui-ux-pro-max 定调),深浅双主题
- Swagger: http://localhost:8080/swagger-ui.html

- **账号**:邮箱验证码注册 + 密码登录(BCrypt + JWT);游客模式,不登录也能问答和浏览,需要时再弹登录框

## 评测结果(2026-09-12,原 574 题语料)

golden 集去自环:LLM 把题干改写成真实问法(1146 条)+ 长答案细节探针(39 条)+ 域外负样本(10 条)。
固定种子抽样 n=200 + 全量探针,同一集合对比三种配置:

| 指标 | 旧分块 | parent-child 无重排 | **parent-child + rerank(上线配置)** |
|---|---|---|---|
| 混合 recall@5 | 0.965 | 0.945 | **0.955** |
| 混合 MRR | 0.865 | 0.804 | 0.840 |
| **探针 recall@1** | 0.615 | 0.692 | **0.795** |
| **探针 recall@5** | 0.846 | 0.923 | **0.949** |
| 平均检索延迟 | 194ms | 207ms | 506ms |

后来新增的 98 道 AI 题不在这份评测集里;要覆盖它们需重跑 `POST /api/admin/eval/build`。
**不要用 `mode=self`(默认值)看效果**:那是拿题干原文搜同一题,recall 恒为假满分,只能验证链路没坏。

## 语料(corpus/)状态与约定

- 布局:corpus/interview/<板块group>/<页>.md;板块按**站侧边栏编号**分组(06架构设计=消息队列+分布式+系统设计)。
- 两种题级结构:java/database/mq/cap 页 h2=类目 h3=题;systemdesign 与 tools 页 h2 直接当题。切题规则=**叶子级标题即一道题**。
- 小林coding 13 页:java5 + database2(mysql94/redis48) + architecture3(mq38/cap19/systemdesign14) + tools3(linux/git/docker)。
- AI 面试题(`corpus/interview/ai/`,来自 xiaolinnote.com,一题一页):agent30 / rag23 / tools22 / llm23。
  每专题合并成一个 md;文章内小节标题降为加粗段落(否则会被当成题),丢弃开头情景对话里故意答错的反面示范。

## 抓取

```bash
.venv/Scripts/python -m crawler.main            # 小林coding PAGES 清单全部
.venv/Scripts/python -m crawler.ai_pages        # AI 面试题;--topic rag 只抓一个专题
.venv/Scripts/python crawler/probe_groups.py    # 摸底站点板块结构
```
已处理:标题锚点、代码行号、页顶广告、嵌套 strong、全角标点保真、图片只留外链引用。每篇间隔 1 秒。
