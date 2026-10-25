<template>
  <div class="page">
    <header class="head">
      <div>
        <h1>知识库</h1>
        <p class="sub">574 道题按板块组织,点开看权威原文;可本地过滤。</p>
      </div>
    </header>

    <div class="kb">
      <!-- 左:树 -->
      <aside class="tree-pane">
        <el-input v-model="kw" placeholder="过滤题目…" clearable class="search">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>

        <div v-if="kw" class="flatten">
          <button v-for="q in filtered" :key="q.id" class="fitem" :class="{ on: q.id === currentId }"
                  @click="pick(q.id)">
            <span class="dot" :style="{ background: groupColor(q.group) }" />
            <span class="fline">
              <span class="fq">{{ q.question }}</span>
              <span class="fmeta">{{ q.group }} · {{ q.docTitle }}</span>
            </span>
          </button>
          <p v-if="!filtered.length" class="none">没有匹配的题</p>
        </div>

        <el-scrollbar v-else class="tree-scroll">
          <div v-for="g in tree" :key="g.group" class="g">
            <div class="g-head" @click="toggleGroup(g.group)">
              <span class="g-name">{{ gLabel(g.group) }}</span>
              <span class="g-count">{{ countGroup(g.group) }}</span>
            </div>
            <div v-if="openGroups.includes(g.group)" class="g-body">
              <div v-for="doc in g.docs" :key="doc.slug" class="doc">
                <div class="doc-title">{{ doc.title }}</div>
                <template v-for="cat in doc.categories" :key="cat.category || ''">
                  <div v-if="cat.category" class="cat">{{ cat.category }}</div>
                  <div class="qs">
                    <button v-for="q in cat.questions" :key="q.id" class="q"
                            :class="{ on: q.id === currentId }" @click="pick(q.id)">
                      {{ q.question }}
                    </button>
                  </div>
                </template>
              </div>
            </div>
          </div>
        </el-scrollbar>
      </aside>

      <!-- 右:原文 -->
      <section class="detail-pane">
        <div v-if="!detail" class="placeholder">
          <el-icon :size="44"><Collection /></el-icon>
          <p>从左侧挑一道题,查看完整标准答案</p>
        </div>
        <template v-else>
          <div class="detail-head">
            <div class="tags">
              <span class="tag accent">{{ detail.group }}</span>
              <span v-if="detail.category" class="tag">{{ detail.category }}</span>
            </div>
            <h2 class="dq">{{ detail.question }}</h2>
            <div class="dmeta">
              <span>{{ detail.title }}</span>
              <el-button link type="primary" tag="a" :href="detail.url" target="_blank">
                原站文章 <el-icon><TopRight /></el-icon>
              </el-button>
            </div>
          </div>
          <el-scrollbar class="dbody">
            <MarkdownView :content="detail.content" />
          </el-scrollbar>
        </template>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { corpusApi } from '../api/client'
import type { GroupNode, ChunkFull } from '../api/types'
import { Search, Collection, TopRight } from '@element-plus/icons-vue'
import MarkdownView from '../components/MarkdownView.vue'

const tree = ref<GroupNode[]>([])
const kw = ref('')
const currentId = ref<number | null>(null)
const detail = ref<ChunkFull | null>(null)
const openGroups = ref<string[]>([])

const groupLabels: Record<string, string> = {
  java: 'Java', database: '数据库', architecture: '架构设计', tools: '工具命令', ai: 'AI 大模型',
}

const allQuestions = computed(() => {
  const out: Array<{ id: number; question: string; group: string; docTitle: string; category: string | null }> = []
  for (const g of tree.value) {
    for (const d of g.docs) {
      for (const c of d.categories) {
        for (const q of c.questions) {
          out.push({ id: q.id, question: q.question, group: g.group, docTitle: d.title, category: c.category })
        }
      }
    }
  }
  return out
})

const filtered = computed(() => {
  const k = kw.value.trim().toLowerCase()
  if (!k) return []
  return allQuestions.value.filter((q) => q.question.toLowerCase().includes(k)).slice(0, 200)
})

function countGroup(group: string) {
  const g = tree.value.find((x) => x.group === group)
  if (!g) return 0
  return g.docs.reduce((s, d) => s + d.categories.reduce((s2, c) => s2 + c.questions.length, 0), 0)
}
function gLabel(g: string) { return groupLabels[g] || g }
function groupColor(g: string): string {
  const map: Record<string, string> = { java: 'var(--accent)', database: 'var(--info)', architecture: 'var(--warn)', tools: 'var(--text-dim)', ai: 'var(--danger)' }
  return map[g] || 'var(--accent)'
}
function toggleGroup(g: string) {
  openGroups.value = openGroups.value.includes(g)
    ? openGroups.value.filter((x) => x !== g)
    : [...openGroups.value, g]
}

async function pick(id: number) {
  currentId.value = id
  try {
    detail.value = await corpusApi.chunk(id)
  } catch (e) {
    detail.value = null
  }
}

onMounted(async () => {
  try {
    tree.value = await corpusApi.tree()
    openGroups.value = tree.value.map((g) => g.group).slice(0, 2)
  } catch (e) {
    // 后端未就绪时提示
  }
})
</script>

<style scoped>
.page { height: 100%; display: flex; flex-direction: column; }
.head { padding-bottom: 12px; }
h1 { margin: 0 0 4px; font-size: 24px; }
.sub { margin: 0; color: var(--text-dim); font-size: 13.5px; }

.kb { flex: 1; min-height: 0; display: grid; grid-template-columns: 360px 1fr; gap: 16px; }
.tree-pane { border: 1px solid var(--border); border-radius: var(--radius); background: var(--bg-panel); display: flex; flex-direction: column; min-height: 0; padding: 10px; gap: 8px; }
.search :deep(.el-input__wrapper) { background: var(--bg-soft); box-shadow: none; }
.tree-scroll { flex: 1; }
.g { margin-bottom: 4px; }
.g-head { display: flex; justify-content: space-between; padding: 8px 6px; border-radius: 8px; cursor: pointer; }
.g-head:hover { background: var(--bg-hover); }
.g-name { font-weight: 600; font-size: 13.5px; }
.g-count { color: var(--text-faint); font-size: 12px; font-family: var(--font-display); }
.g-body { padding-left: 6px; }
.doc { margin: 4px 0 8px; }
.doc-title { font-size: 11px; color: var(--text-faint); text-transform: none; margin: 2px 6px 4px; }
.cat { font-size: 11px; font-weight: 600; color: var(--text-dim); margin: 6px 6px 2px; }
.qs { display: flex; flex-direction: column; gap: 1px; }
.q {
  text-align: left; border: 0; background: transparent; color: var(--text);
  padding: 5px 8px; border-radius: 7px; font-size: 12.8px; line-height: 1.45;
  transition: background var(--t-fast) var(--ease);
}
.q:hover { background: var(--bg-hover); }
.q.on { background: var(--accent-soft); color: var(--accent); }

.flatten { display: flex; flex-direction: column; gap: 3px; overflow-y: auto; }
.fitem { display: flex; gap: 8px; align-items: flex-start; text-align: left; border: 0; background: transparent; padding: 7px 6px; border-radius: 8px; color: var(--text); }
.fitem:hover { background: var(--bg-hover); }
.fitem.on { background: var(--accent-soft); }
.dot { flex: none; width: 8px; height: 8px; border-radius: 50%; margin-top: 6px; }
.fline { display: flex; flex-direction: column; min-width: 0; }
.fq { font-size: 12.6px; line-height: 1.4; }
.fmeta { font-size: 10.5px; color: var(--text-faint); }
.none { color: var(--text-faint); font-size: 12.5px; text-align: center; margin-top: 16px; }

.detail-pane { border: 1px solid var(--border); border-radius: var(--radius); background: var(--bg-panel); min-height: 0; display: flex; flex-direction: column; overflow: hidden; }
.placeholder { margin: auto; text-align: center; color: var(--text-faint); }
.placeholder p { margin-top: 10px; font-size: 13px; }
.detail-head { padding: 16px 20px 12px; border-bottom: 1px solid var(--border); }
.tags { display: flex; gap: 6px; margin-bottom: 8px; }
.tag { font-size: 11px; padding: 2px 8px; border-radius: 999px; background: var(--bg-soft); color: var(--text-dim); }
.tag.accent { background: var(--accent-soft); color: var(--accent); font-weight: 600; }
.dq { margin: 0 0 6px; font-size: 18px; line-height: 1.45; }
.dmeta { display: flex; justify-content: space-between; align-items: center; color: var(--text-faint); font-size: 12px; }
.dbody { flex: 1; padding: 14px 22px 30px; }

@media (max-width: 900px) {
  .kb { grid-template-columns: 1fr; }
  .tree-pane { max-height: 300px; }
}
</style>
