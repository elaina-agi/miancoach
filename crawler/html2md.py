"""html2md.py — 把 VuePress 服务端渲染的 HTML 正文,转成干净的 Markdown。

只处理 <main class="page"> 里的 .theme-default-content 容器,导航/侧栏/页脚全部丢弃。

重点处理的问题(全是实际从页面里发现并验证过的):
  1. 标题里的锚点 <a class="header-anchor">#</a>  -> 剔除,不然标题会带上 "#"
  2. 代码块 <div class="language-xx"><pre><code>…  -> 转 ```xx 围栏
     且 VuePress 的行号是混在正文文本里的("2\\t    int num") -> 启发式剥离
  3. 页顶的训练营广告 <p align=center><a><img></a></p>  -> 识别并丢弃
  4. 作者手写的嵌套 <strong> 没闭合(HTML 里合法嵌套)   -> inline 兜底,不强转记号
  5. 正文图片 <p><img src="https://cdn.xiaolincoding.com//picgo/…">
     只保留 md 图片引用,不下载二进制
"""
from __future__ import annotations

import posixpath
import re
from urllib.parse import urlparse

from bs4 import BeautifulSoup, NavigableString, Tag

# 会被当"块级"处理、不该出现在 inline 文本里的标签
_BLOCK_TAGS = frozenset(
    {"p", "div", "pre", "table", "ul", "ol", "blockquote", "hr", "li", "h1", "h2", "h3", "h4", "h5", "h6"}
)

_LINE_NUM_RE = re.compile(r"^[ \t]*\d+[ \t]+")
_LANG_RE = re.compile(r"language-([A-Za-z0-9_+\-]*)")
# 空 alt / 无意义 alt,渲染时统一降级成 [图]
_MEANINGLESS_ALT = {"", "img", "image"}


def _clean_ws(s: str) -> str:
    """段落文字只做空白收敛:任何空白(含换行、全角空格、<br> 产生的 \\n)都并成单个空格。

    刻意不做 NFKC——全角逗号 ， 若被规范化成半角 ,,会让标题(未规范化)与正文标点风格不一致。
    \\s 对 str 默认含 Unicode 空白(U+3000 全角空格也在内),无需显式列。
    """
    return re.sub(r"\s+", " ", s).strip()


# --------------------------------------------------------------------------- #
# inline:段落 / 标题 / 单元格内部的行内内容
# --------------------------------------------------------------------------- #
def inline(node, _in_emph: bool = True) -> str:
    """把一个混合节点(文本 + inline 标签)转成 md 文本。"""
    if isinstance(node, NavigableString):
        return str(node)
    parts: list[str] = []
    for child in node.children:
        if isinstance(child, NavigableString):
            parts.append(str(child))
            continue
        if not isinstance(child, Tag):
            continue
        name = child.name
        cls = child.get("class") or []
        # 跳过 VuePress 锚点与无障碍隐藏元素
        if name == "a" and ("header-anchor" in cls or "sr-only" in cls):
            continue
        if name == "br":
            parts.append("\n")
        elif name in ("code", "tt"):
            t = child.get_text()
            parts.append(f"`{t.replace('`', '\\\\`')}`")
        elif name in ("strong", "b"):
            parts.append(_emph(child, ("strong", "b"), "**") if _in_emph else inline(child, False))
        elif name in ("em", "i"):
            parts.append(_emph(child, ("em", "i"), "*") if _in_emph else inline(child, False))
        elif name == "a":
            t = inline(child).strip()
            href = child.get("href") or ""
            if not t or href.startswith("#"):
                parts.append(t)  # 纯锚点链接没意义,只留文字
            else:
                parts.append(f"[{t}]({href})")
        elif name == "img":
            parts.append(_img_md(child))
        elif name in ("sup", "sub", "u", "del", "mark", "small", "abbr", "font", "span", "kbd", "samp"):
            parts.append(inline(child, _in_emph))
        else:
            # 未知行内标签,降级为纯文本(不丢内容)
            parts.append(inline(child, False))
    return "".join(parts)


def _emph(node: Tag, names: tuple[str, ...], marker: str) -> str:
    """strong/em 转换:若内部还有同名标签(作者没闭合),直接拍平丢内容,避免出 '**a **b** c**' 这种脏东西。"""
    if node.find(names):  # 存在嵌套同名标签
        return inline(node, _in_emph=False)
    inner = inline(node, _in_emph=False).strip()
    return f"{marker}{inner}{marker}" if inner else ""


def _img_md(img: Tag) -> str:
    alt = (img.get("alt") or "").strip()
    if alt in _MEANINGLESS_ALT:
        alt = "图"
    alt = alt.replace("\n", " ").replace("|", "\\|")
    src = img.get("src") or ""
    return f"![{alt}]({src})"


def _text_without_img(node: Tag) -> str:
    """统计节点里"图片之外"的文字,用来判断一个 <p> 是不是纯图片段。"""
    out: list[str] = []
    for ch in node.children:
        if isinstance(ch, NavigableString):
            out.append(str(ch))
        elif isinstance(ch, Tag):
            if ch.name == "img":
                continue
            if ch.name == "a" and ch.find("img") is not None:
                continue
            out.append(inline(ch))
    return "".join(out).strip()


# --------------------------------------------------------------------------- #
# block:段落级内容
# --------------------------------------------------------------------------- #
def _render_code(pre: Tag) -> str:
    """把 <pre class="language-xx"><code>…</code></pre> 转成 ```xx 围栏。"""
    code = pre.find("code") or pre
    lang = ""
    for el in (pre, pre.parent):
        m = _LANG_RE.search(" ".join(el.get("class") or []))
        if m and m.group(1):
            lang = m.group(1)
            break
    text = _strip_line_numbers(code.get_text())
    return f"```{lang}\n{text}\n```"


def _strip_line_numbers(text: str) -> str:
    """VuePress 把行号混进了代码文本(第二行起是 '2\\t    int num')。启发式剥离。

    判定标准:超过一半非空行都以 '数字+空白' 开头,则认为是行号,全部剥掉。
    只剥每行开头的行号,行内/注释里的数字不受影响。
    """
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    lines = text.split("\n")
    non_empty = [ln for ln in lines if ln.strip()]
    if not non_empty:
        return text.strip("\n")
    hit = sum(1 for ln in non_empty if _LINE_NUM_RE.match(ln))
    if hit / len(non_empty) < 0.5:
        return text.strip("\n")
    stripped = [_LINE_NUM_RE.sub("", ln) for ln in lines]
    while stripped and not stripped[0].strip():
        stripped.pop(0)
    return "\n".join(stripped).rstrip("\n")


def _render_table(table: Tag) -> str:
    rows = []
    for tr in table.find_all("tr"):
        cells = [inline(td).replace("|", "\\|").replace("\n", " ").strip() for td in tr.find_all(["th", "td"])]
        if cells:
            rows.append(cells)
    if not rows:
        return ""
    # 第一行当表头,补分隔行
    head = rows[0]
    ncol = max(len(r) for r in rows)
    pad = lambda r: r + [""] * (ncol - len(r))
    lines = ["| " + " | ".join(pad(head)) + " |"]
    lines.append("|" + "|".join([" --- "] * ncol) + "|")
    for r in rows[1:]:
        lines.append("| " + " | ".join(pad(r)) + " |")
    return "\n".join(lines)


def _render_list(node: Tag, depth: int = 0) -> str:
    """<ul>/<ol> 递归渲染。列表项的"正文"与"嵌套块"(嵌套列表/代码/段落)分开处理。"""
    res: list[str] = []
    order = 0
    indent = "    " * depth  # 4 空格缩进,CommonMark 下对 "- " 与 "1. " 都安全
    for li in node.children:
        if not isinstance(li, Tag) or li.name != "li":
            continue
        order += 1
        marker = f"{order}. " if node.name == "ol" else "- "
        text = _li_inline_text(li)
        lines: list[str] = []
        if text:
            lines.append(indent + marker + text)
        # li 里可能跟的块级子节点
        for blk in li.find_all(["ul", "ol", "pre", "table", "blockquote", "p"], recursive=False):
            if blk.name in ("ul", "ol"):
                lines.append(_render_list(blk, depth + 1))
            else:
                md = render(blk).strip()
                lines.append("\n".join(indent + "    " + ln for ln in md.split("\n")))
        res.append("\n".join(lines))
    return "\n".join(res)


def _li_inline_text(li: Tag) -> str:
    """取 <li> 直接行内文字(忽略内部的 ul/ol/代码块等块级内容,它们单独渲染)。"""
    parts: list[str] = []
    for ch in li.children:
        if isinstance(ch, NavigableString):
            parts.append(str(ch))
        elif isinstance(ch, Tag) and ch.name not in _BLOCK_TAGS:
            parts.append(inline(ch))
    return "".join(parts).strip()


def _is_promo(el: Tag) -> bool:
    """判断是否广告横幅:一个 <p>,内容基本只有 <a><img> 可点大图(训练营引流)。"""
    if el.name != "p":
        return False
    a = el.find("a", recursive=False)
    if a is None or a.find("img") is None:
        return False
    # p 里除了那个链接外没有其它文字/块级内容才判为广告
    return not any(isinstance(c, NavigableString) and c.strip() for c in el.children) and not el.find_all(
        ["ul", "ol", "pre", "table"], recursive=False
    )


def render(node: Tag, depth: int = 0) -> str:
    """把任意块级节点转成 md(多行字符串,不含前后空行)。"""
    if isinstance(node, NavigableString) or not isinstance(node, Tag):
        return ""
    name = node.name
    if name == "hr":
        return ""
    if name in ("h1", "h2", "h3", "h4", "h5", "h6"):
        text = inline(node).strip()
        return f"{'#' * int(name[1])} {text}" if text else ""
    if name == "p":
        img = node.find("img")
        if img is not None and not _text_without_img(node):
            return _img_md(img)  # 纯图片段 -> 只留 md 图片引用
        return _clean_ws(inline(node))
    if name == "img":
        return _img_md(node)
    if name == "pre":
        return _render_code(node)
    if name == "table":
        return _render_table(node)
    if name == "ul" or name == "ol":
        return _render_list(node, depth)
    if name == "blockquote":
        inner = "\n\n".join(ln for c in _block_children(node) if (ln := render(c).strip()))
        return "\n".join(f"> {ln}" if ln else ">" for ln in inner.split("\n"))
    if name == "div":
        # VuePress 代码块外包裹 <div class="language-xx">,取里面真正的 <pre>
        pre = node.find("pre")
        if pre is not None:
            return _render_code(pre)
        # 其它普通 div,当容器递归
        return "\n\n".join(ln for c in _block_children(node) if (ln := render(c).strip()))
    # 兜底:按块级子节点递归,不丢内容
    return "\n\n".join(ln for c in _block_children(node) if (ln := render(c).strip()))


def _block_children(node: Tag) -> list[Tag]:
    """容器的直接块级子节点(过滤空白文本与空块)。"""
    return [ch for ch in node.children if isinstance(ch, Tag)]


def _container(soup: BeautifulSoup) -> Tag:
    """定位正文容器。逐级降级:main 内主题容器 -> 任意 .theme-default-content -> main。"""
    sel = soup.select_one("main.page .theme-default-content, main .theme-default-content")
    if sel is None:
        sel = soup.select_one(".theme-default-content")
    if sel is None:
        sel = soup.find("main")
    if sel is None:
        raise ValueError("页面里找不到正文容器,可能站点结构变了,需要重新探针")
    return sel


def _frontmatter(soup: BeautifulSoup, url: str) -> str:
    """从 <meta> 和 URL 拼 YAML frontmatter。"""
    def meta(attr: str) -> str:
        for tag in soup.find_all("meta"):
            key = tag.get("property") or tag.get("name") or ""
            if key == attr and tag.get("content"):
                return tag["content"].strip()
        return ""

    title = meta("og:title") or (soup.title.string or "").split("|")[0].strip()
    lastmod = meta("article:modified_time")[:10]  # 2026-07-25T… -> 2026-07-25
    path = posixpath.normpath(urlparse(url).path)
    category = posixpath.dirname(path).strip("/") or "root"
    lines = [
        "---",
        f"title: {title}",
        f"url: {url}",
        f"lastmod: {lastmod}" if lastmod else "lastmod:",
        f"category: {category}",
        "source: xiaolincoding",
        "---",
    ]
    return "\n".join(lines)


def html_to_markdown(html: str, url: str) -> tuple[str, dict]:
    """HTML -> 'frontmatter + 正文 md'。同时返回基础统计供日志/验收。"""
    soup = BeautifulSoup(html, "html.parser")
    container = _container(soup)
    blocks: list[str] = []
    stats = {"h2": 0, "h3": 0, "images": 0}
    for child in _block_children(container):
        if _is_promo(child):
            continue
        md = render(child).strip()
        if not md:
            continue
        blocks.append(md)
        if child.name == "h2":
            stats["h2"] += 1
        elif child.name == "h3":
            stats["h3"] += 1
        stats["images"] += len(child.find_all("img"))
    body = "\n\n".join(blocks) + "\n"
    md = _frontmatter(soup, url) + "\n\n" + body
    return md, stats
