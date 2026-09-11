"""probe_groups.py — 探针:列出 /interview/ 目录页的真实分组结构(板块名 -> 文章页链接)。

一次性摸底工具;站点改版、或想知道某板块下有哪几页时用它。
"""
import re
import sys

import requests
from bs4 import BeautifulSoup

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)
URL = "https://xiaolincoding.com/interview/"
PAGE_RE = re.compile(r"/interview/[A-Za-z0-9_]+\.html$")


def main() -> int:
    resp = requests.get(URL, headers={"User-Agent": UA, "Accept-Language": "zh-CN,zh;q=0.9"}, timeout=30)
    resp.encoding = "utf-8"
    print(f"HTTP {resp.status_code}  bytes={len(resp.content)}")
    soup = BeautifulSoup(resp.text, "html.parser")
    main = soup.select_one("main .theme-default-content") or soup.find("main") or soup.body
    assert main is not None

    groups: list[tuple[str, list[tuple[str, str]]]] = []
    cur: tuple[str, list] | None = None
    for el in main.find_all(["h2", "h3", "a"]):
        if el.name in ("h2", "h3"):
            title = el.get_text(" ", strip=True)
            cur = (title, [])
            groups.append(cur)
        elif el.name == "a" and cur is not None:
            href = el.get("href") or ""
            if PAGE_RE.search(href) and href not in [h for _, h in cur[1]]:
                cur[1].append((el.get_text(" ", strip=True), href))

    total = 0
    for title, links in groups:
        if not links:
            continue
        print(f"\n[{title}]  ({len(links)} 页)")
        for t, h in links:
            print(f"    {t:<24} {h}")
        total += len(links)
    print(f"\n总计 {len([g for g in groups if g[1]])} 个板块 / {total} 页")
    return 0


if __name__ == "__main__":
    sys.exit(main())
