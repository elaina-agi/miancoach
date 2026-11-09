// 站点级常量:开源仓库、题库来源。关于页和各处的来源标注都从这里取,改一处全站生效

export const REPO_URL = 'https://github.com/abkkkbb/miancoach'
export const REPO_NAME = 'abkkkbb/miancoach'

export interface CorpusSource {
  name: string
  site: string
  url: string
  covers: string
}

/**
 * 题库全部来自小林(公众号「小林coding」)的两个网站,版权归原作者。
 * 已取得作者同意公开使用,条件是注明来源——所以每道题都要能链回原文。
 */
export const CORPUS_AUTHOR = '小林coding'
export const CORPUS_SOURCES: CorpusSource[] = [
  {
    name: '小林coding',
    site: 'xiaolincoding.com',
    url: 'https://xiaolincoding.com/',
    covers: 'Java、MySQL、Redis、消息队列、分布式、系统设计、Linux、Git、Docker',
  },
  {
    name: '小林面试笔记',
    site: 'xiaolinnote.com',
    url: 'https://xiaolinnote.com/',
    covers: 'AI 面试题：Agent、RAG、工具调用、大模型工程',
  },
]

/** 按原文链接的域名认出是哪个站,按钮上写"小林coding 原文"而不是笼统的"原站文章";认不出返回 null */
export function sourceName(url: string | null | undefined): string | null {
  if (!url) return null
  let host: string
  try {
    host = new URL(url).hostname
  } catch {
    return null
  }
  return CORPUS_SOURCES.find((s) => host === s.site || host.endsWith('.' + s.site))?.name ?? null
}
