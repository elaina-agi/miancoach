"""Browser regression with mocked APIs against an existing MianCoach dev server.
Run from repo root: python web/tests/source-favorites.browser.py
Requires Playwright Python + Chromium. Optional: BAGUWEN_WEB_TEST_URL, BAGUWEN_TEST_BROWSER.
Never forwards API calls to the real server and never invokes a real model.
"""
import json
import os
from pathlib import Path
from urllib.parse import urlparse
from playwright.sync_api import sync_playwright, expect

BASE = os.environ.get('BAGUWEN_WEB_TEST_URL', 'http://localhost:5173')
OUT = Path('.tmp/source-favorites-ui')
OUT.mkdir(parents=True, exist_ok=True)
QUESTIONS = ['MCP 协议通常采用什么通信方式？', 'MCP 由哪几部分组成？', '已删除的历史题目']
REFS = [dict(chunkId=i + 1, question=q, group='ai', title='LLM 工具调用面试题', category=None,
             url='https://example.test/source', excerpt='测试来源摘要') for i, q in enumerate(QUESTIONS)]
SAVED = {'alice': set(), 'bob': set()}
WRITES = []
FAIL = set()


def api(route):
    req = route.request
    path = urlparse(req.url).path
    # Vite 的 /src/api/client.ts 不是后端 API,必须放行模块请求。
    if not path.startswith('/api/'):
        route.continue_()
        return
    user = req.headers.get('authorization', '').removeprefix('Bearer ')

    def reply(body, status=200):
        route.fulfill(status=status, content_type='application/json', body=json.dumps(body, ensure_ascii=False))

    if path == '/api/auth/me':
        reply(dict(id=user, email=f'{user}@example.test', nickname=user, role='USER'))
    elif path == '/api/auth/login':
        who = req.post_data_json['email'].split('@')[0]
        reply(dict(token=who, user=dict(id=who, email=f'{who}@example.test', nickname=who, role='USER')))
    elif path == '/api/meta':
        reply(dict(chatReal=True, embedReal=True, totalChunks=2, totalSegs=4, docs=1, index=dict(indexed=True, compatible=True)))
    elif path == '/api/corpus/tree':
        reply([dict(group='ai', docs=[dict(slug='tools', title='工具调用', url='', categories=[dict(category=None, questions=[dict(id=i+1, question=q) for i, q in enumerate(QUESTIONS[:2])])])])])
    elif path == '/api/chat/sessions':
        reply([dict(id='test-session', title='MCP 问答', createdAt='2026-11-09T00:00:00Z', updatedAt='2026-11-09T00:00:00Z')])
    elif path == '/api/chat/session/test-session':
        reply([dict(id=1, role='user', content=QUESTIONS[0]), dict(id=2, role='assistant', content='测试回答，参考来源 [1]。', refs=REFS)])
    elif path == '/api/chat/ask':
        body = 'event: refs\ndata: ' + json.dumps(REFS, ensure_ascii=False) + '\n\nevent: delta\ndata: 测试回答，参考来源 [1]。\n\nevent: done\ndata: {}\n\n'
        route.fulfill(status=200, content_type='text/event-stream', body=body)
    elif path.startswith('/api/corpus/chunk/'):
        id = int(path.rsplit('/', 1)[-1])
        if id == 3:
            reply(dict(error='题目不存在'), 404)
        else:
            reply(dict(**REFS[id-1], docId=1, content='## 简要回答\n\n这是一份独立于 AI 回答的知识库参考答案。\n\n' + ('参考内容，供核对来源和练习复习。\n\n' * 30)))
    elif path.startswith('/api/favorites/'):
        id = int(path.rsplit('/', 1)[-1])
        if user not in SAVED:
            reply(dict(error='请先登录'), 401)
        elif req.method == 'GET' and (user, id) in FAIL:
            FAIL.remove((user, id))
            reply(dict(error='模拟收藏状态读取失败'), 503)
        else:
            if req.method == 'PUT':
                SAVED[user].add(id)
                WRITES.append((user, id, 'PUT'))
            elif req.method == 'DELETE':
                SAVED[user].discard(id)
                WRITES.append((user, id, 'DELETE'))
            reply(dict(favorited=id in SAVED[user]))
    else:
        reply(dict(error='Unexpected API in mock test: ' + path), 404)


with sync_playwright() as p:
    launch = {'headless': True}
    if os.environ.get('BAGUWEN_TEST_BROWSER'):
        launch['executable_path'] = os.environ['BAGUWEN_TEST_BROWSER']
    browser = p.chromium.launch(**launch)
    errors = []
    ctx = browser.new_context(viewport=dict(width=1440, height=910), reduced_motion='reduce')
    ctx.route('**/api/**', api)
    ctx.add_init_script("localStorage.setItem('miancoach-token', 'alice'); localStorage.setItem('miancoach-theme','light')")
    page = ctx.new_page()
    page.on('pageerror', lambda e: errors.append(str(e)))
    page.goto(BASE + '/chat/test-session')
    page.locator('.source-chip').first.click()
    panel = page.locator('.src')
    expect(panel.get_by_role('heading', name=QUESTIONS[0], exact=True)).to_be_visible()
    panel.get_by_role('button', name='收藏题目', exact=True).click()
    expect(panel.get_by_role('button', name='取消收藏这道题')).to_be_enabled()
    panel.get_by_role('tab', name='2', exact=True).click()
    expect(panel.get_by_role('button', name='收藏题目', exact=True)).to_be_enabled()
    panel.get_by_role('tab', name='1', exact=True).click()
    expect(panel.get_by_role('button', name='取消收藏这道题')).to_be_enabled()
    panel.get_by_role('button', name='取消收藏这道题').click()
    expect(panel.get_by_role('button', name='收藏题目', exact=True)).to_be_enabled()
    assert WRITES == [('alice', 1, 'PUT'), ('alice', 1, 'DELETE')]
    for width in [1440, 1024, 768, 375]:
        page.set_viewport_size(dict(width=width, height=910))
        expect(panel.get_by_role('button', name='收藏题目', exact=True)).to_be_enabled()
        assert panel.evaluate('(el) => el.scrollWidth <= el.clientWidth'), f'panel overflow at {width}'
        footer = panel.locator('.src-foot')
        assert footer.evaluate('(el) => [...el.children].every(c => c.getBoundingClientRect().right <= el.getBoundingClientRect().right + 1)'), f'footer overflow at {width}'
        page.screenshot(path=str(OUT / f'source-{width}.png'), full_page=True)
    # 错误状态允许重试,不伪装成未收藏。
    FAIL.add(('alice', 2))
    panel.get_by_role('tab', name='2', exact=True).click()
    expect(panel.get_by_role('button', name='重试收藏状态')).to_be_enabled()
    panel.get_by_role('button', name='重试收藏状态').click()
    expect(panel.get_by_role('button', name='收藏题目', exact=True)).to_be_enabled()
    # 历史引用对应题目已删除时,不提供可操作的收藏入口。
    panel.get_by_role('tab', name='3', exact=True).click()
    expect(panel.locator('.src-fail')).to_be_visible()
    expect(panel.locator('.favorite-control')).to_have_count(0)
    panel.get_by_role('tab', name='1', exact=True).click()
    expect(panel.get_by_role('button', name='收藏题目', exact=True)).to_be_enabled()
    # 暗色截图（侧栏作为抽屉时先关抽屉再切主题）。
    panel.get_by_role('button', name='关闭', exact=True).click()
    page.get_by_title('切换到深色').click()
    page.locator('.source-chip').first.click()
    expect(panel.get_by_role('button', name='收藏题目', exact=True)).to_be_enabled()
    page.screenshot(path=str(OUT / 'source-mobile-dark.png'), full_page=True)

    # 游客在来源抽屉里登录后继续收藏,不丢当前问答/来源。
    guest = browser.new_context(viewport=dict(width=375, height=910), reduced_motion='reduce')
    guest.route('**/api/**', api)
    gp = guest.new_page()
    gp.on('pageerror', lambda e: errors.append(str(e)))
    gp.goto(BASE + '/chat')
    gp.locator('textarea').fill(QUESTIONS[0])
    gp.get_by_role('button', name='发送', exact=True).click()
    gp.locator('.source-chip').first.click()
    source = gp.locator('.src')
    source.get_by_role('button', name='收藏题目', exact=True).click()
    expect(gp.get_by_role('heading', name='登录 MianCoach')).to_be_visible()
    gp.locator('input[autocomplete="username"]').fill('bob@example.test')
    gp.locator('input[autocomplete="current-password"]').fill('test-password')
    gp.locator('button[type="submit"]').click()
    expect(source.get_by_role('button', name='取消收藏这道题')).to_be_enabled()
    assert SAVED['bob'] == {1} and not SAVED['alice']
    expect(source.get_by_role('heading', name=QUESTIONS[0], exact=True)).to_be_visible()
    source.get_by_role('button', name='关闭', exact=True).click()
    gp.goto(BASE + '/kb/1')
    expect(gp.get_by_role('button', name='取消收藏这道题')).to_be_enabled()
    gp.get_by_role('button', name='取消收藏这道题').click()
    expect(gp.get_by_role('button', name='收藏题目', exact=True)).to_be_enabled()
    assert not SAVED['bob']
    assert not errors, errors
    browser.close()
    print(json.dumps({'result': 'PASS', 'checks': ['source add/remove', 'source switching', 'status retry', 'deleted source', 'responsive footer', 'dark theme', 'guest login continuation in sheet', 'shared knowledge favorite']}, ensure_ascii=False))
