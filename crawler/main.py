"""main.py — 爬取入口。

用法(在项目根目录):
    .venv/Scripts/python -m crawler.main            # 抓 PAGES 清单里全部页
    .venv/Scripts/python -m crawler.main --url URL   # 抓单页(清单外的走 URL 镜像路径)
    .venv/Scripts/python -m crawler.main --out PATH  # 抓单页并指定输出

输出布局:corpus/<大类> / <板块group> / <topic>.md
    corpus/interview/java/basic.md          # Java基础面试题
    corpus/interview/java/collections.md    # Java集合面试题
    ...
每页一个 md = "存档单元"(和 sitemap 的页面级 lastmod 对齐);文件夹只是浏览/分组用。
"""
from __future__ import annotations

import argparse
import pathlib
import posixpath
import sys
import time
from urllib.parse import urlparse

from crawler.fetch import get_html
from crawler.html2md import html_to_markdown

PROJECT_ROOT = pathlib.Path(__file__).resolve().parent.parent
CORPUS_ROOT = PROJECT_ROOT / "corpus"

# 页面清单:(URL, 板块group, 文件名)
# group 对齐站点侧边栏分组(Java面试篇…),filename 用能对上 URL 的 ASCII slug。
PAGES: list[tuple[str, str, str]] = [
    # —— Java 面试篇 ——
    ("https://xiaolincoding.com/interview/java.html", "java", "basic"),
    ("https://xiaolincoding.com/interview/collections.html", "java", "collections"),
    ("https://xiaolincoding.com/interview/juc.html", "java", "concurrent"),
    ("https://xiaolincoding.com/interview/jvm.html", "java", "jvm"),
    ("https://xiaolincoding.com/interview/spring.html", "java", "spring"),
    # —— 数据库面试篇(04) ——
    ("https://xiaolincoding.com/interview/mysql.html", "database", "mysql"),
    ("https://xiaolincoding.com/interview/redis.html", "database", "redis"),
    # —— 架构设计面试篇(06):消息队列 + 分布式 + 系统设计 ——
    ("https://xiaolincoding.com/interview/mq.html", "architecture", "mq"),
    ("https://xiaolincoding.com/interview/cap.html", "architecture", "distributed"),
    ("https://xiaolincoding.com/interview/systemdesign.html", "architecture", "systemdesign"),
    # —— 工具命令面试篇(07) ——
    ("https://xiaolincoding.com/interview/linux.html", "tools", "linux"),
    ("https://xiaolincoding.com/interview/git.html", "tools", "git"),
    ("https://xiaolincoding.com/interview/docker.html", "tools", "docker"),
    # 注:AI-Agent 面试篇(N 题)在本站没有正文,5 个板块均指向外站 xiaolinnote.com,暂不入清单
]


def _legacy_default_out(url: str) -> pathlib.Path:
    """清单外 URL 的兜底:按 URL 路径镜像成 corpus/interview/ 下的 md。"""
    path = posixpath.normpath(urlparse(url).path)
    if path.endswith(".html"):
        path = path[:-5] + ".md"
    elif not path.endswith(".md"):
        path += ".md"
    return CORPUS_ROOT / path.lstrip("/")


def out_for(url: str) -> pathlib.Path:
    for u, group, name in PAGES:
        if u == url:
            return CORPUS_ROOT / "interview" / group / f"{name}.md"
    return _legacy_default_out(url)


def crawl_one(url: str, out: pathlib.Path, print_log: bool = True) -> pathlib.Path:
    html = get_html(url)
    md, stats = html_to_markdown(html, url)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(md, encoding="utf-8", newline="\n")
    if print_log:
        lines = md.splitlines()
        print(f"OK   {url.split('/')[-1]:<18} h2={stats['h2']:<3} h3={stats['h3']:<4} img={stats['images']:<3} "
              f"{len(md.encode('utf-8')) // 1024}KB -> {out.relative_to(PROJECT_ROOT)}")
    return out


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="抓取 xiaolincoding 面试页 -> 干净 md")
    ap.add_argument("--url", default=None, help="抓单页;不传则抓 PAGES 清单全部")
    ap.add_argument("--out", default=None, help="单页时的输出路径,默认按清单/URL 规则")
    ap.add_argument("--sleep", type=float, default=1.0, help="批量时每页间隔秒数(默认1s,守规矩别打太快)")
    args = ap.parse_args(argv)

    if args.url:
        out = pathlib.Path(args.out) if args.out else out_for(args.url)
        crawl_one(args.url, out)
        return 0

    # 批量:先全量抓下来再写,中途某页失败不阻塞其它页
    results: list[tuple[str, str]] = []  # (url, out)
    failed: list[str] = []
    for url, group, name in PAGES:
        out = CORPUS_ROOT / "interview" / group / f"{name}.md"
        try:
            crawl_one(url, out)
            results.append((url, str(out.relative_to(PROJECT_ROOT))))
        except Exception as exc:  # 网络错误/解析异常,记下来继续
            failed.append(f"{url}: {exc}")
        time.sleep(args.sleep)
    print(f"\n完成 {len(results)}/{len(PAGES)} 页;失败 {len(failed)}")
    for f in failed:
        print(f"  FAIL {f}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
