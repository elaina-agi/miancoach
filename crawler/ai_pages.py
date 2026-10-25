"""ai_pages.py — 抓 xiaolinnote.com 的 AI 面试题(Agent / RAG / LLM 工具调用 / 大模型工程)。

用法(在项目根目录):
    .venv/Scripts/python -m crawler.ai_pages              # 抓全部 4 个专题
    .venv/Scripts/python -m crawler.ai_pages --topic rag  # 只抓一个
输出:corpus/interview/ai/<topic>.md,每个专题合并成一个文件。

和 main.py(xiaolincoding)的区别,都是探针实测出来的:
  1. 一题一页,不是一页几十题。先读 /ai/ 目录页拿到题目链接(按目录顺序),再逐篇抓。
  2. 正文容器是 #markdown-content(vuepress-theme-hope),不是 .theme-default-content。
  3. 每篇自带 h1 题目 + h2「💡 简要回答 / 📝 详细解析 / 🎯 面试总结」+ h3 小节。
     MdParser 的规则是"叶子标题 = 一道题",原样合并会把这些小节全当成题。
     所以合并时:题目统一成 h2,文章内部的 h2/h3 全部转成加粗段落,不再是标题。
  4. 开头那段「👔面试官 / 🙋我」的情景对话里,"我"的回答是故意答错的反面教材。
     这段内容会成为检索材料和模拟面试批改的标准答案,留着会误导模型,所以丢弃,
     从第一个 h2(简要回答)开始取。
  5. 文末 <hr> 之后是"关注公众号"引流 + 二维码,丢弃。
"""
from __future__ import annotations

import argparse
import re
import sys
import time

from bs4 import BeautifulSoup, Tag

from crawler.fetch import get_html
from crawler.html2md import _block_children, _img_md, _is_promo, inline, render
from crawler.main import CORPUS_ROOT, PROJECT_ROOT

BASE = "https://xiaolinnote.com"
INDEX_URL = f"{BASE}/ai/"

# 专题 slug(= URL 路径段 = 输出文件名) -> 专题标题
TOPICS: dict[str, str] = {
    "agent": "Agent 面试题",
    "rag": "RAG 面试题",
    "tools": "LLM 工具调用面试题",
    "llm": "大模型工程面试题",
}

_PROMO_RE = re.compile(r"公众号|关注|加群|二维码|扫码|训练营")
_NUM_PREFIX_RE = re.compile(r"^\s*\d+\s*[.、．]\s*")


def discover(topic: str) -> list[str]:
    """从 /ai/ 目录页按出现顺序取该专题的题目链接(去重,排除专题首页 *_info.html)。"""
    soup = BeautifulSoup(get_html(INDEX_URL), "html.parser")
    pat = re.compile(rf"/ai/{topic}/([^/#?]+\.html)")
    urls: list[str] = []
    for a in soup.find_all("a", href=True):
        m = pat.search(a["href"])
        if m and not m.group(1).endswith("_info.html"):
            u = f"{BASE}/ai/{topic}/{m.group(1)}"
            if u not in urls:
                urls.append(u)
    if not urls:
        raise ValueError(f"目录页里找不到 {topic} 的题目链接,站点结构可能变了")
    return urls


def _heading_text(h: Tag) -> str:
    """theme-hope 把标题文字整个包在 <a class="header-anchor"> 里(xiaolincoding 的锚点只是旁边一个 #),
    html2md.inline 会把这个 a 当锚点整个跳过,标题就成了空串。标题里没有要保留的格式,直接取纯文本。"""
    return re.sub(r"\s+", " ", h.get_text(" ", strip=True)).strip()


def _meta(soup: BeautifulSoup, key: str) -> str:
    tag = soup.find("meta", attrs={"property": key}) or soup.find("meta", attrs={"name": key})
    return (tag.get("content") or "").strip() if tag else ""


def _is_tail_junk(el: Tag) -> bool:
    """文末要丢的块:分隔线、二维码图、引流文字。"""
    if el.name in ("hr", "figure"):
        return True
    if el.name == "p":
        text = el.get_text(" ", strip=True)
        return not text or bool(_PROMO_RE.search(text)) or _is_promo(el)
    return False


def _unwrap_figures(container: Tag) -> None:
    """<figure><img><figcaption>图注</figcaption></figure> -> <p><img alt=图注></p>。
    html2md 的 render 对 figure 没有专门处理,figcaption 里的纯文字会被丢掉;这里先改写成它认识的结构。"""
    for fig in container.find_all("figure"):
        img = fig.find("img")
        if img is None:
            fig.decompose()
            continue
        cap = fig.find("figcaption")
        if cap and cap.get_text(strip=True) and not (img.get("alt") or "").strip():
            img["alt"] = cap.get_text(" ", strip=True)
        p = BeautifulSoup("<p></p>", "html.parser").p
        p.append(img.extract())
        fig.replace_with(p)


def article_to_md(url: str) -> tuple[str, str, str]:
    """抓一篇,返回 (题目, 正文 md, lastmod)。正文里不含任何 markdown 标题。"""
    soup = BeautifulSoup(get_html(url), "html.parser")
    box = soup.select_one("#markdown-content")
    if box is None:
        raise ValueError(f"找不到 #markdown-content: {url}")

    h1 = box.find("h1")
    question = _NUM_PREFIX_RE.sub("", _heading_text(h1) if h1 else _meta(soup, "og:title"))
    if not question:
        raise ValueError(f"取不到题目: {url}")
    lastmod = _meta(soup, "article:modified_time")[:10]

    kids = _block_children(box)
    # 从第一个 h2 开始(跳过 h1 和开头的情景对话);没有 h2 的页面退化为 h1 之后全部
    start = next((i for i, k in enumerate(kids) if k.name == "h2"), None)
    if start is None:
        start = next((i + 1 for i, k in enumerate(kids) if k.name == "h1"), 0)
    kids = kids[start:]
    while kids and _is_tail_junk(kids[-1]):
        kids.pop()

    blocks: list[str] = []
    for k in kids:
        if k.name in ("h1", "h2", "h3", "h4", "h5", "h6"):
            text = _heading_text(k)
            if text:
                blocks.append(f"**{text}**")  # 降成加粗段落,MdParser 不会把它当题
            continue
        if k.name == "figure":
            img = k.find("img")
            if img is not None:
                blocks.append(_img_md(img))
            continue
        if _is_promo(k):
            continue
        _unwrap_figures(k)
        md = render(k).strip()
        if md:
            blocks.append(md)
    body = "\n\n".join(blocks)
    if re.search(r"^#{1,6}\s", re.sub(r"```.*?```", "", body, flags=re.S), re.M):
        raise ValueError(f"正文里仍有 markdown 标题,会被 MdParser 当成题: {url}")
    return question, body, lastmod


def crawl_topic(topic: str, sleep: float) -> int:
    urls = discover(topic)
    print(f"[{topic}] 目录里 {len(urls)} 题")
    sections: list[str] = []
    seen: set[str] = set()
    lastmods: list[str] = []
    failed: list[str] = []
    for i, url in enumerate(urls, 1):
        try:
            q, body, lastmod = article_to_md(url)
        except Exception as exc:  # 单篇失败不阻塞整个专题
            failed.append(f"{url}: {exc}")
            print(f"  FAIL {url}: {exc}")
            continue
        if q in seen:  # 题干相同会撞库里的 UNIQUE(doc_id, question)
            failed.append(f"{url}: 题目重复「{q}」")
            continue
        seen.add(q)
        lastmods.append(lastmod)
        # 每题末尾带原文链接:知识库里能点回去看图文版,也是对原作者的署名
        sections.append(f"## {q}\n\n{body}\n\n来源:[小林面试笔记 · 原文]({url})")
        print(f"  {i:>2}/{len(urls)} OK  {q[:40]}  ({len(body) // 1000}k 字)")
        time.sleep(sleep)

    title = TOPICS[topic]
    front = "\n".join([
        "---",
        f"title: {title}",
        f"url: {BASE}/ai/{topic}/{topic}_info.html",
        f"lastmod: {max(lastmods) if lastmods else ''}",
        f"category: ai/{topic}",
        "source: xiaolinnote",
        "---",
    ])
    out = CORPUS_ROOT / "interview" / "ai" / f"{topic}.md"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(f"{front}\n\n# {title}\n\n" + "\n\n".join(sections) + "\n", encoding="utf-8", newline="\n")
    print(f"[{topic}] 写入 {len(sections)}/{len(urls)} 题 -> {out.relative_to(PROJECT_ROOT)}")
    for f in failed:
        print(f"  FAIL {f}")
    return len(failed)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="抓 xiaolinnote.com AI 面试题 -> corpus/interview/ai/*.md")
    ap.add_argument("--topic", choices=list(TOPICS), default=None, help="只抓一个专题;不传抓全部")
    ap.add_argument("--sleep", type=float, default=1.0, help="每篇间隔秒数(默认 1s,守规矩别打太快)")
    args = ap.parse_args(argv)
    sys.stdout.reconfigure(encoding="utf-8")  # Windows 控制台默认 GBK,题目里的 emoji/中文会报错
    topics = [args.topic] if args.topic else list(TOPICS)
    failed = sum(crawl_topic(t, args.sleep) for t in topics)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
