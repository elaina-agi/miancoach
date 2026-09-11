"""fetch.py — 带 UA/重试的取页,统一处理编码。"""
from __future__ import annotations

import time

import requests

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)

_SESSION = requests.Session()
_SESSION.headers.update(
    {
        "User-Agent": UA,
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    }
)


def get_html(url: str, timeout: int = 20, tries: int = 3) -> str:
    """GET 一个页面,失败自动退避重试,返回按 UTF-8 解码的文本。

    站点用 <meta charset="utf-8">,但响应头未必带 charset,
    requests 默认会误判成 ISO-8859-1 导致中文乱码,所以强制 utf-8。
    """
    last_err: Exception | None = None
    for i in range(tries):
        try:
            resp = _SESSION.get(url, timeout=timeout)
            resp.raise_for_status()
            resp.encoding = "utf-8"
            return resp.text
        except requests.RequestException as exc:  # 网络错误 / 4xx / 5xx
            last_err = exc
            if i < tries - 1:
                time.sleep(1.5 * (i + 1))  # 退避:1.5s -> 3s
    raise RuntimeError(f"抓取失败({tries} 次): {url}: {last_err}")
