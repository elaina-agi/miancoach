<template>
  <div class="page-scroll">
    <div class="about">
      <header class="intro">
        <h1>关于 MianCoach</h1>
        <p>一个 AI 面试陪练：知识库问答、题目收藏、模拟面试与复盘，围绕同一套技术面试题库展开。回答提供来源引用，随时可以点开核对原文。</p>
      </header>

      <!-- 题库来源放最前面:这是公开本站的前提 -->
      <section class="panel source" aria-labelledby="about-source">
        <div class="panel-head">
          <BookMarked :size="18" class="head-icon" />
          <h2 id="about-source">题库来源</h2>
        </div>
        <!-- 中文段落里换行会被渲染成空格,所以写成一行 -->
        <p class="lead">本站的题目和标准答案全部来自 <b>{{ CORPUS_AUTHOR }}</b>（作者小林）的两个网站，<b>版权归原作者所有</b>。想系统地学，推荐直接去原站阅读。</p>
        <div class="sites">
          <a v-for="s in CORPUS_SOURCES" :key="s.site" :href="s.url" target="_blank" rel="noopener noreferrer" class="site">
            <span class="site-top">
              <span class="site-name">{{ s.name }}</span>
              <span class="site-go">去原站<ExternalLink :size="13" /></span>
            </span>
            <span class="site-domain">{{ s.site }}</span>
            <span class="site-covers">{{ s.covers }}</span>
          </a>
        </div>
      </section>

      <section class="panel" aria-labelledby="about-repo">
        <div class="panel-head">
          <GithubMark :size="17" class="head-icon" />
          <h2 id="about-repo">开源仓库</h2>
        </div>
        <p>前后端代码、检索链路和评测脚本都在 GitHub 上。题库原文版权归原作者，不随仓库分发。</p>
        <a :href="REPO_URL" target="_blank" rel="noopener noreferrer" class="btn btn-secondary repo">
          <GithubMark :size="15" />{{ REPO_NAME }}<ExternalLink :size="13" />
        </a>
      </section>

      <section class="panel" aria-labelledby="about-how">
        <div class="panel-head">
          <Sparkles :size="18" class="head-icon" />
          <h2 id="about-how">如何设计</h2>
        </div>
        <ul class="how">
          <li><b>检索</b><span>精确匹配、向量、关键词三路召回，再用 cross-encoder 统一重排；长答案切成小段，细节也能找到</span></li>
          <li><b>生成</b><span>只依据检索到的原文作答，结论后标 [n] 引用；题库里没有的问题会直接说没有，不硬编</span></li>
          <li><b>批改</b><span>模拟面试对照这道题的原文逐点给分，列出答到的点和漏掉的点</span></li>
          <li><b>技术栈</b><span>Spring Boot 3 · Vue 3 · PostgreSQL + pgvector</span></li>
        </ul>
      </section>

      <p class="note"><Info :size="14" />AI 生成的回答和批改可能有偏差，请以原文为准。</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { BookMarked, ExternalLink, Info, Sparkles } from 'lucide-vue-next'
import GithubMark from '../components/GithubMark.vue'
import { CORPUS_AUTHOR, CORPUS_SOURCES, REPO_NAME, REPO_URL } from '../lib/site'
</script>

<style scoped>
.page-scroll { height: 100%; overflow-y: auto; }
.about { max-width: 760px; margin: 0 auto; padding: 40px 28px 64px; display: flex; flex-direction: column; gap: 16px; }

.intro { margin-bottom: 12px; }
.intro h1 { font-size: 28px; letter-spacing: -0.02em; }
.intro p { margin-top: 8px; font-size: 15px; line-height: 1.7; color: var(--ink-2); }

.panel { padding: 20px 22px; border: 1px solid var(--line); border-radius: var(--r-lg); background: var(--surface); display: flex; flex-direction: column; gap: 12px; }
.panel p { font-size: 14px; line-height: 1.75; color: var(--ink-2); }
.panel-head { display: flex; align-items: center; gap: 8px; }
.panel-head h2 { font-size: 15.5px; }
.head-icon { color: var(--accent); }

.source { border-color: var(--accent-line); }
.lead b { color: var(--ink); font-weight: 600; }

.sites { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.site { display: flex; flex-direction: column; gap: 4px; padding: 14px 16px; border: 1px solid var(--line); border-radius: var(--r); background: var(--bg); transition: border-color var(--t) var(--ease), background var(--t) var(--ease); }
.site:hover { border-color: var(--accent-line); background: var(--accent-soft); }
.site-top { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.site-name { font-size: 15px; font-weight: 600; }
.site-go { display: inline-flex; align-items: center; gap: 3px; font-size: 12.5px; font-weight: 500; color: var(--accent); }
.site-domain { font-family: var(--font-mono); font-size: 12.5px; color: var(--ink-3); }
.site-covers { margin-top: 4px; font-size: 13px; line-height: 1.6; color: var(--ink-2); }

.repo { align-self: flex-start; }

.how { margin: 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 10px; }
.how li { display: grid; grid-template-columns: 4.5em 1fr; gap: 12px; font-size: 14px; line-height: 1.7; }
.how b { font-weight: 600; }
.how span { color: var(--ink-2); }

.note { display: flex; align-items: center; gap: 6px; font-size: 12.5px; color: var(--ink-3); }

@media (max-width: 720px) {
  .about { padding: 24px 16px 40px; }
  .intro h1 { font-size: 22px; }
  .sites { grid-template-columns: 1fr; }
  .how li { grid-template-columns: 1fr; gap: 2px; }
}
</style>
