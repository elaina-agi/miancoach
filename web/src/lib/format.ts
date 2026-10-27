// 时间与分数的展示规则。后端时间都是 ISO-8601 UTC,new Date() 会转成本地时区,
// 所以"今天/昨天"的边界一律按本地零点算,不能直接切 ISO 字符串的日期部分。

const p2 = (n: number) => String(n).padStart(2, '0')

function startOfDay(d: Date): number {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()
}

export function hhmm(iso: string): string {
  const d = new Date(iso)
  return `${p2(d.getHours())}:${p2(d.getMinutes())}`
}

/** 今天 14:05 / 昨天 09:30 / 9月15日 / 2025年3月2日 */
export function friendlyDate(iso: string, withTime = true): string {
  const d = new Date(iso)
  const today = startOfDay(new Date())
  const day = startOfDay(d)
  const t = withTime ? ` ${hhmm(iso)}` : ''
  if (day === today) return `今天${t}`
  if (day === today - 86400000) return `昨天${t}`
  if (d.getFullYear() === new Date().getFullYear()) return `${d.getMonth() + 1}月${d.getDate()}日${t}`
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日`
}

/**
 * 会话列表的分组:今天 / 昨天 / 近 7 天 / 更早。
 * 不按天铺开:用上几个月后每天一个小标题、底下只有一条,列表比内容还长。越久越粗是会话列表的惯例。
 */
export function dayBucket(iso: string): '今天' | '昨天' | '近 7 天' | '更早' {
  const today = startOfDay(new Date())
  const t = new Date(iso).getTime()
  if (t >= today) return '今天'
  if (t >= today - 86400000) return '昨天'
  if (t >= today - 6 * 86400000) return '近 7 天'
  return '更早'
}

export type ScoreBand = 'good' | 'mid' | 'bad'

/** 分数分三档。颜色之外一定配文字标签(优秀/及格…),不只靠颜色传达 */
export function scoreBand(score: number): ScoreBand {
  if (score >= 75) return 'good'
  if (score >= 50) return 'mid'
  return 'bad'
}

export function scoreLabel(score: number): string {
  if (score >= 90) return '优秀'
  if (score >= 75) return '良好'
  if (score >= 60) return '及格'
  if (score >= 40) return '待加强'
  return '薄弱'
}
