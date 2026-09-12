// 哨兵文件,没有任何可执行内容,也不要被任何入口引用。
// 原因:code-inspector-plugin@2.0.9 的 transformIndexHtml 硬编码传入 file: "main.js",
// 并用 fs.existsSync('main.js')(按进程工作目录解析)做早退检查——项目根没有这个文件时,
// 点击跳编辑器的客户端运行时会被静默注入成空串(表现为:按快捷键无高亮遮罩)。
// 本项目的真实入口是 src/main.ts,所以放这个空文件骗过检查。删除它 = 插件失效。
// 参考:@code-inspector/vite/dist/index.mjs 的 transformIndexHtml 与
//       @code-inspector/core 的 getCodeWithWebComponent 早退分支。
