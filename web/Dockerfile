# ── 构建:pnpm 版本与本地一致(node_modules/.modules.yaml 记的是 pnpm@10) ──
FROM node:22-alpine AS build
WORKDIR /src
RUN npm install -g pnpm@10
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
RUN pnpm build

# ── 运行:nginx 托管静态文件,/api 反代到后端 ──
FROM nginx:stable-alpine
# 官方镜像启动时会把 templates/*.template 里的 ${环境变量} 替换后写进 conf.d/
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /src/dist /usr/share/nginx/html
# 后端地址,默认是 compose 里的服务名;换部署方式时用 -e BACKEND_ADDR=host:port 覆盖
ENV BACKEND_ADDR=server:8080
EXPOSE 80
