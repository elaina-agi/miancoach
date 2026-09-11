"""probe.py — 摸清目标页 HTML 结构,为写解析器做准备(一次性探针,不是正式代码)"""
import sys
from collections import Counter
import requests
from bs4 import BeautifulSoup

URL = "https://xiaolincoding.com/interview/java.html"
HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
    "Accept-Language": "zh-CN,zh;q=0.9",
}

def main():
    resp = requests.get(URL, headers=HEADERS, timeout=20)
    resp.encoding = resp.apparent_encoding or "utf-8"
    print(f"HTTP {resp.status_code}  bytes={len(resp.content)}")
    soup = BeautifulSoup(resp.text, "html.parser")

    # 1) 找正文容器候选:class 含 content 的 div
    cands = soup.select("div[class*=content]")
    print("\n== div[class*=content] ==")
    for c in cands[:10]:
        print(f"  class={c.get('class')!r}  children_tags={Counter(x.name for x in c.find_all(recursive=False)) if c else ''}")

    # 2) h1 及其祖先链路(确定正文起点)
    h1 = soup.find("h1")
    if h1:
        print("\n== h1 祖先链路 ==")
        chain = []
        node = h1
        while node and node.name != "html":
            cls = node.get("class", [])
            chain.append(f"{node.name}.{'.'.join(cls)}")
            node = node.parent
        print("  < " + " < ".join(reversed(chain)))

    # 3) 标题大纲 h1-h4(前 30 个),验证"类目 h2 / 题目 h3"假设
    print("\n== 标题大纲 (前 30) ==")
    for i, h in enumerate(soup.find_all(["h1", "h2", "h3", "h4"])[:30]):
        txt = h.get_text(" ", strip=True)
        print(f"  {h.name}  #{h.get('id','')}  {txt[:50]!r}")

    # 4) 全文各元素计数
    body = soup.body
    print("\n== 元素计数(全文) ==")
    for sel in ["img", "pre", "table", "blockquote", "a", "p", "ul", "ol"]:
        print(f"  {sel}: {len(body.select(sel))}")

    # 5) 打几个 img 的 src 看看是不是 CDN 外链
    print("\n== img src 样例 (前 5) ==")
    for img in body.select("img")[:5]:
        print(f"  alt={img.get('alt','')[:20]!r}  src={img.get('src','')[:90]!r}")

    # 6) 定位最可能的正文容器后,把容器 HTML 存盘供人工核对
    main = soup.find("main") or h1.parent if h1 else None
    if main:
        print(f"\nmain tag: {main.name}  class={main.get('class')}")
        with open("_probe_main.html", "w", encoding="utf-8") as f:
            f.write(str(main))
        print("已把 main 容器 HTML 存到 _probe_main.html")

if __name__ == "__main__":
    sys.exit(main())
