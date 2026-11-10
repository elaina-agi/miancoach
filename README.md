# MianCoach · AI 面试陪练

MianCoach 是一个基于检索增强生成（RAG）的技术面试陪练应用，将知识库问答、题目收藏、模拟面试和练习复盘整合在同一套题库中。系统从题库原文检索参考资料，生成带来源引用的回答，并根据标准资料提供作答反馈。

仓库：[elaina-agi/miancoach](https://github.com/elaina-agi/miancoach)。原仓库名为 `baguwen-rag`；现有部署保持数据库与环境变量兼容，迁移注意事项见下方「命名与升级兼容」。

## 主要功能

- **知识库问答**：支持流式回答、来源引用和多轮追问，可打开引用核对原文。
- **题库浏览**：按板块、类目浏览题目，支持搜索高亮、题目定位和原文图片查看。
- **个人收藏**：从知识库或问答来源原文收藏整道题，按用户隔离，支持板块筛选和分页复习。
- **模拟面试**：按范围抽题，对照题库原文批改，展示得分、命中的知识点和遗漏项。
- **练习复盘**：查看场次记录、逐题批改、得分趋势及按板块聚合的薄弱点，生成场次总结。
- **账号与历史**：支持邮箱验证码注册、密码登录和 JWT 鉴权；游客可体验问答与练习，登录后按账号保存历史。
- **管理后台**：支持后台重建索引、查看进度，以及用户搜索、禁用、角色设置和删除。
- **模型配置**：生成、向量、重排分别配置；支持 Mock 联调、重排失败降级及可选备用生成模型。

> AI 生成的回答和评分可能存在偏差，来源引用与相关性阈值不能保证答案完全正确，请结合原文核验。

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 后端 | Java 17、Spring Boot 3、JdbcTemplate、Flyway |
| 前端 | Vue 3、TypeScript、Vite、Vue Router |
| 数据存储 | PostgreSQL 16、pgvector |
| 模型接入 | OpenAI 兼容接口、Embedding、Cross-encoder Rerank |
| 语料处理 | Python、Requests、Beautiful Soup、Java Markdown 解析与分块 |
| 接口与部署 | Springdoc / Swagger UI、SSE、Docker Compose、Nginx、GitHub Actions |

## 系统架构

```text
语料入库
题库网页 → Python 抓取与清洗 → Markdown
                                  ↓
                    Java 按题切分 → 长答案切子段
                                  ↓
                       批量 Embedding → pgvector
                                  ↓
                        记录模型与分块方案指纹

在线问答
用户问题 → 必要时改写追问 → 精确 / 向量 / 关键词召回
                                  ↓
                         去重 → Rerank → Top N
                                  ↓
                   相关性检查 → 原文与命中子段组装
                                  ↓
                       生成模型 → SSE 回答与引用
```

### 核心设计

- **父子分块**：每道题保存完整答案，长答案额外建立子段向量。子段命中后关联父题，在生成上下文中优先放入命中片段，再按字符预算补充父题正文。
- **多路召回与重排**：归一化题干精确匹配、题目级与子段级向量检索、关键词匹配共同构建候选，重排模型统一打分后返回最终材料；重排失败时保留召回原序。
- **索引一致性**：检索前校验模型名称、向量维度及分块方案，发现不一致时要求重建索引，避免混用不同向量空间。
- **相关性检查**：无精确命中且向量分数、重排分数同时低于配置阈值时返回拒答，避免直接基于低相关材料生成回答。
- **流式输出与来源记录**：通过 `refs → delta → done` 事件返回引用和增量文本；拒答在 `done` 数据中标记 `noAnswer=true`。登录用户的回答与引用快照随会话保存。
- **基于原文批改**：模拟面试按题目 ID 获取标准资料，将用户作答与资料一并提交给生成模型，返回结构化反馈。

核心实现入口：[IngestService](<server/src/main/java/com/autumn/rag/service/IngestService.java>)、[RetrieveService](<server/src/main/java/com/autumn/rag/service/RetrieveService.java>)、[AskService](<server/src/main/java/com/autumn/rag/service/AskService.java>)。

## 项目结构

```text
miancoach/
├── crawler/             网页抓取与 Markdown 清洗
├── corpus/              本地语料，运行爬虫生成，不随仓库分发
├── server/              Spring Boot 后端、数据库迁移与测试
├── web/                 Vue 3 前端
├── compose.yml          本地 pgvector 数据库
├── compose.prod.yml     容器部署配置
└── .env.example         环境变量示例
```

## 快速启动

### 环境要求

- Docker / Docker Compose
- JDK 17、Maven 3.9
- Node.js 22、pnpm
- Python 3

以下步骤从项目根目录开始。首次获取项目：

```bash
git clone https://github.com/elaina-agi/miancoach.git
cd miancoach
```

Python 示例使用 Windows 虚拟环境路径，Linux / macOS 请将 `.venv/Scripts/` 替换为 `.venv/bin/`。

### 1. 准备配置

复制[环境变量示例](<.env.example>)：

```bash
cp .env.example .env
```

PowerShell 也可使用：

```powershell
Copy-Item .env.example .env
```

按需配置模型、数据库、邮件服务与 JWT 密钥。未配置模型 Key 且 `provider=auto` 时使用 Mock，仅用于验证接口和交互流程，不代表真实检索、生成或批改效果。

### 2. 获取语料

题库原文版权归原作者，不随仓库分发。请先阅读本文末尾的「题库来源与版权」，再按需抓取：

```bash
python -m venv .venv
.venv/Scripts/pip install requests beautifulsoup4
.venv/Scripts/python -m crawler.main
.venv/Scripts/python -m crawler.ai_pages
```

前者抓取小林coding 配置的页面，后者抓取小林面试笔记中的 AI 专题。输出位于 `corpus/interview/<板块>/<页面>.md`。

### 3. 启动数据库

```bash
docker compose up -d
```

默认容器为 `baguwen-pg`，宿主机端口为 `15432`。后端启动时由 Flyway 执行数据库迁移。

### 4. 启动后端

在一个终端中执行：

```bash
cd server
mvn spring-boot:run
```

另开终端检查服务状态：

```bash
curl http://localhost:8080/api/meta
```

`chatReal`、`embedReal` 可用于确认是否接入真实模型；首次启动尚未建立索引时，需要继续完成下方的管理员设置和索引初始化。

### 5. 启动前端

在项目根目录另开终端：

```bash
cd web
pnpm install
pnpm dev
```

- 前端：http://localhost:5173
- 后端：http://localhost:8080
- Swagger：http://localhost:8080/swagger-ui.html
- 请求示例：[server/api.http](<server/api.http>)

开发环境的前端 `/api` 请求代理到后端 `8080` 端口。

### 6. 初始化索引

1. 在前端注册账号。
2. 按下一节设置初始管理员，然后重新登录。
3. 打开右上角头像菜单 →「管理后台」→「重建索引」。
4. 等待任务成功，确认 `/api/meta` 返回的 `index.compatible` 为 `true`。

重建任务在后台执行，页面可查看阶段与进度；同一时间只允许一个重建任务。

## 管理员

初始管理员需要在数据库中手动设置。将下方邮箱替换为已注册账号的邮箱：

```bash
docker exec -it baguwen-pg psql -U baguwen -d baguwen_rag -c "UPDATE app_user SET role = 'ADMIN' WHERE email = '<你的邮箱>';"
```

若修改了数据库用户或数据库名，请同步调整命令。设置后重新登录，头像菜单中将出现「管理后台」。后续可在用户管理中设置其他管理员。

管理接口 `/api/admin/**` 由后端统一校验身份：未登录返回 `401`，普通用户返回 `403`。后台禁止对当前账号执行禁用、删除或取消自身管理员角色。

容器部署时，可通过 `docker compose -f compose.prod.yml exec pg psql ...` 连接数据库执行同样的 SQL。

## 模型与检索配置

配置示例见[.env.example](<.env.example>)，完整默认值见[application.yml](<server/src/main/resources/application.yml>)。

| 模型端 | 用途 | 更换后的处理 |
| --- | --- | --- |
| `chat` 生成端 | 问答、批改、场次总结、追问改写 | 不需要重建向量索引 |
| `embed` 向量端 | 语料向量化与查询向量化 | 更换模型或维度后必须重建索引 |
| `rerank` 重排端 | 对召回候选进行相关性打分 | 不需要重建索引；失败时降级 |

示例配置使用百炼 `qwen-plus` 生成，以及硅基流动 `BAAI/bge-m3` 向量模型、`BAAI/bge-reranker-v2-m3` 重排模型。可替换为兼容接口，模型可用性、费用和限额以服务商说明为准。

注意事项：

- 显式配置 `BAGUWEN_EMBED_BASE_URL`，确保地址与模型匹配，不要仅修改模型名称。
- 重排 Key 未设置时会回退使用向量端 Key；两端使用不同服务商时，应显式配置各自的 Key 和地址。
- 默认 `vector-k=10`：父题和子段向量各取 10 条；`candidate-k=20`：最多 20 个候选进入重排；`top-n=5`：最终返回 5 条材料。
- 修改分块规则并更新方案标识后，也需要重建索引。
- 备用生成模型为可选配置；未配置备用 Key 时，不启用备用模型。
- 未配置真实邮件服务时，验证码写入本地运行日志，可搜索 `[mock mail]`；默认路径见[日志配置](<server/src/main/resources/application.yml#L138-L140>)。生产环境应使用真实邮件服务。

## 命名与升级兼容

对外品牌使用 **MianCoach**，仓库使用 `miancoach`，前后端包名为 `miancoach-web` / `miancoach-server`。本次改名不需要重新建库、重新索引或删除任何数据。

- **旧克隆**：执行 `git remote set-url origin https://github.com/elaina-agi/miancoach.git`。本地目录可以继续用旧名字；若要改成 `miancoach`，先停止该目录中的前后端进程、关闭 IDE，再从父目录重命名并重新打开。不要边运行边移动目录。
- **已有部署**：保留 `BAGUWEN_*` 环境变量、`baguwen_rag` 数据库、Compose 项目名、容器和数据卷名。特别不要改 Compose 顶层 `name`、原有 `-p` 参数或执行 `down -v`，否则可能使用另一套空数据卷。
- **镜像名称**：GitHub 仓库改名不代表 GHCR 镜像自动改名或重定向。新 CI 会发布 `miancoach-server/web`，并对原仓库继续发布 `baguwen-rag-server/web` 兼容别名；需要先提交并运行该 CI，新名称才有可拉取的产物。
- **JAR 名称**：源码构建产物改为 `target/miancoach-server-0.1.0.jar`；容器内仍叫 `app.jar`。若服务器直接用 `java -jar` 启动，更新脚本中的 JAR 路径；本地旧 JAR 不是新版程序。
- **不改旧迁移**：已经发布的 Flyway SQL 不因品牌改名而重写，避免 checksum 校验失败。Java 包名 `com.autumn.rag`、浏览器 `miancoach-*` 存储键与重建锁标识保持兼容。
- **旧代码引用**：其他工作副本、服务器 clone、CI 脚本如固定了旧 GitHub 地址，请同步更新。这里的源码更新不等于已推送、发布镜像或完成线上部署。

## Docker 部署

[GitHub Actions 配置](<.github/workflows/docker.yml>)会在主分支相关代码更新后构建并推送镜像，当前目标架构为 `linux/arm64`：

- `ghcr.io/elaina-agi/miancoach-server`
- `ghcr.io/elaina-agi/miancoach-web`

原仓库构建还会向 `ghcr.io/elaina-agi/baguwen-rag-server` / `baguwen-rag-web` 发布同一版本的兼容别名。确认本次改名后的 CI 构建成功、所需标签可用后，在部署环境配置中设置：

```dotenv
MIANCOACH_IMAGE_PREFIX=ghcr.io/elaina-agi/miancoach
```

不设置时，[部署配置](<compose.prod.yml>)继续使用旧镜像别名，避免在新镜像尚未发布时直接拉取失败。

镜像支持 `latest`、提交 SHA 标签，以及 `v*` 发布标签对应的语义化版本标签。Fork 仓库后设置 `MIANCOACH_IMAGE_PREFIX=ghcr.io/<你的用户名>/miancoach`，CI 会发布到当前 owner 名下；其他部署架构需调整构建配置。新 GHCR 包的可见性和拉取权限需单独核对，不能假定沿用旧包设置。

在部署目录准备好[compose.prod.yml](<compose.prod.yml>)、环境配置和已生成的语料目录，然后执行：

```bash
# 镜像需要鉴权时，先使用具有 read:packages 权限的凭据登录
# docker login ghcr.io -u <GitHub用户名>
docker compose -f compose.prod.yml pull
docker compose -f compose.prod.yml up -d
```

首次部署仍需注册账号、设置管理员并重建索引。

部署前请更换示例数据库密码、设置稳定的 `BAGUWEN_JWT_SECRET`，并按需配置模型 Key、邮件服务与访问额度。默认只将后端 `8080` 绑定到本机回环地址，前端由 Nginx 提供服务并反向代理 `/api`；SSE 已关闭代理缓冲。

## 检索评测

提供两种评测模式：

- **`mode=golden`**：使用独立改写问题、长答案细节探针和域外负样本，衡量 Recall@K、MRR、延迟及拒答表现。
- **`mode=self`**：用题干原文检索对应题目，适合链路回归，不应将其结果当作真实问法的检索质量。

管理员可使用以下接口，具体请求及鉴权示例见[server/api.http](<server/api.http>)：

```text
POST /api/admin/eval/build
GET  /api/admin/eval?mode=golden&n=200&topN=5
```

构建评测集会调用生成模型，完整评测也会产生模型调用，请留意耗时与服务额度。重建索引或更新语料后，应重新构建匹配的评测集。

### 历史对比结果

以下为项目记录的 **2026-09-12、原 574 道题语料**上的评测结果，不代表当前所有配置和数据版本的效果。评测集包含 1146 条改写问题、39 条细节探针和 10 条域外负样本；使用固定种子抽样 `n=200`，并评测全量细节探针。

| 指标 | 旧分块 | 父子分块，无重排 | 父子分块 + 重排 |
| --- | ---: | ---: | ---: |
| 混合 Recall@5 | 0.965 | 0.945 | 0.955 |
| 混合 MRR | 0.865 | 0.804 | 0.840 |
| 细节探针 Recall@1 | 0.615 | 0.692 | 0.795 |
| 细节探针 Recall@5 | 0.846 | 0.923 | 0.949 |
| 平均检索延迟 | 194 ms | 207 ms | 506 ms |

这组对比体现了细节检索与延迟之间的取舍，并非所有指标同时提升。后续新增的 98 道 AI 题不在该评测集内，覆盖新语料需要重新构建评测集并运行评测。

## 开发与维护

```bash
# 后端测试，在 server 目录执行
mvn test

# 前端检查与构建，在 web 目录执行
pnpm typecheck
pnpm test
pnpm build
```

- **语料结构**：按 Markdown 叶子级标题切题，兼容 `h2 类目 → h3 题目` 与 `h2 题目` 两种布局；答案内部小节应避免被识别为独立题目。
- **更新语料**：新增或修改语料后，通过管理后台重建索引。同文档同完整题干保留题目 ID 和收藏；新增题插入、消失题删除，题干改名或移到别的文档按新题处理。仍会全量计算向量，不是跳过未变内容的增量向量化。
- **重建失败**：入库数据库操作在事务内执行，异常时回滚；更换向量维度需要修改列类型，期间可能阻塞查询。
- **重排降级**：日志中的 `重排失败,降级回召回原序` 表示该次检索未使用重排结果。
- **中文请求**：命令行编码不一致时，可将请求体保存为 UTF-8 文件，并通过 `curl --data-binary @file.json` 发送。
- **抓取工具**：可用 `python -m crawler.ai_pages --topic rag` 抓取指定 AI 专题；站点结构检查工具见 [probe_groups.py](<crawler/probe_groups.py>)。

## 题库来源与版权

题目与标准答案来自原作者**小林**（公众号「小林coding」）的两个网站，**题库内容版权归原作者所有**：

- [小林coding](https://xiaolincoding.com/)：Java、MySQL、Redis、消息队列、分布式、系统设计、Linux、Git、Docker。
- [小林面试笔记](https://xiaolinnote.com/)：Agent、RAG、工具调用与大模型工程等 AI 专题。

本站已取得原作者同意公开使用，条件是注明来源。应用中的题目和引用均链接回原文，「关于」页提供完整来源说明。

题库原文不随本仓库分发。抓取、使用或转载相关内容时，请遵守原站要求，并保留作者署名和原文链接；本项目不授予第三方题库内容的再分发权利。
