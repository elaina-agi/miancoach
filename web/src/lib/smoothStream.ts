/**
  * 流式文本的去抖动渲染。
  *
  * 问题:SSE 的 delta 到达间隔天然不均匀——实测同一次回答里,
  * 相邻两块间隔从 0ms 到 574ms,单块大小从 0 到 47 个字符;
  * 最夸张的一处是四块挤在同一毫秒(共 81 字)后停顿 574ms。
  * 直接 `text += chunk` 会把这种不均匀原样搬到画面上,看起来一顿一顿。
  *
  * 做法:到达的字符先入队,再由 requestAnimationFrame 按帧匀速出队。
  *
  * **不变量:不人为拖长总时长。** 每帧取「缓冲区长度的一个比例」而非固定字数,
  * 所以数据来得快就画得快;流一结束立刻切到更激进的排空速度。
  * 代价是一个约 150~200ms 的尾巴,占一次 5 秒交互的 3%——但它不是零。
  */

export interface SmoothAppender {
    /** 收到一块 delta 时调用:只入队,不立即绘制。 */
    push(chunk: string): void
    /**
     * 收到 done 时调用:切换到加速排空。
     * @param onDrained 缓冲区**真正画完**后触发 —— 光标停止闪烁、发送按钮解锁都挂这里,
     *                  而不是挂在 done 上,否则字还在画按钮就解锁了。
     */
    finish(onDrained?: () => void): void
    /** 中止(点停止 / 组件卸载):停掉 rAF 并丢弃未绘制内容。 */
    cancel(): void
    /** 立即把剩余内容一次性画完(出错时用)。 */
    flushNow(): void
}

/** 流进行中:每帧取缓冲区的 1/8。越积压取得越多,自动跟上到达速度。 */
const STREAM_DIVISOR = 8
/** 流已结束:加速排空,避免尾部被 1 字/帧拖长。 */
const FLUSH_DIVISOR = 3

export function createSmoothAppender(onPaint: (chunk: string) => void): SmoothAppender {
    let pending = ''                            // 已到达、尚未绘制的字符
    let finished = false                        // 上游流是否已结束
    let raf = 0                                 // rAF 句柄;0 表示循环没在跑
    let drainedCb: (() => void) | null = null   // 排空后要通知的回调

    const schedule = () => {
        // 已经排上了就别重复排,否则一帧会画多次,又把节奏打乱
        if (!raf) raf = requestAnimationFrame(tick)
    }

    const fireDrained = () => {
        const cb = drainedCb
        drainedCb = null
        cb?.()
    }

    function tick() {
        raf = 0
        if (pending) {
            const divisor = finished ? FLUSH_DIVISOR : STREAM_DIVISOR
            // Math.max(1, ...) 保证即使只剩几个字也一定在推进,不会卡住
            const n = Math.max(1, Math.ceil(pending.length / divisor))
            onPaint(pending.slice(0, n))
            pending = pending.slice(n)
        }
        if (pending) {
            schedule()
        } else if (finished) {
            fireDrained()   // 画完了才通知外面"真的结束了"
        }
        // 缓冲区空但流没结束 → 循环自然停下,不空转;下次 push() 会重新点火
    }

    return {
        push(chunk) {
            if (!chunk) return
            pending += chunk
            schedule()
        },
        finish(onDrained) {
            finished = true
            drainedCb = onDrained ?? null
            if (pending) schedule()   // 还有货,用 FLUSH_DIVISOR 排空
            else fireDrained()        // 已经空了,直接通知
        },
        cancel() {
            if (raf) cancelAnimationFrame(raf)
            raf = 0
            pending = ''
            finished = true
            drainedCb = null
        },
        flushNow() {
            if (raf) cancelAnimationFrame(raf)
            raf = 0
            if (pending) {
                onPaint(pending)
                pending = ''
            }
            finished = true
            fireDrained()
        },
    }
}