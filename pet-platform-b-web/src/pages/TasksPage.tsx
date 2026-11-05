import { useCallback, useEffect, useState, useSyncExternalStore } from 'react'
import Card from '../components/ui/Card'
import Layout from '../components/layout/Layout'
import { IconCheckCircle, IconClock, IconPaw } from '../components/ui/icons'
import { useToast } from '../components/ui/Toast'
import { storeContext } from '../auth/storeContext'
import { BizError } from '../api/types'
import { fenToYuanText } from '../api/dashboard'
import { SERVICE_ITEM_OPTIONS, bathingApi } from '../api/bathing'
import { TASK_STATUS_LABEL, tasksApi, type TaskRow } from '../api/tasks'
import { isFutureDate } from '../lib/dateText'
import { applyTaskBadgeSnapshot, taskTodayISO } from '../lib/taskBadge'
import { TONE } from '../lib/tone'
import { cx } from '../lib/cx'

/**
 * 「我的任务」（ADR-048）：服务人员侧的待办闭环入口。
 *
 * 这一页存在的理由是后端审计里那条不对称：`task:start`/`task:finish` 遛狗师和医生都有，
 * 但唯一的任务视图 `GET /store/grooming/queue` 只给洗澡/美容两类——**能干活的人看不到自己的活**。
 * 现在读 `GET /store/tasks/mine`（服务端按 token 里的员工身份强制收窄，不接受 employeeId 入参），
 * 动作仍打 `/store/appointments/{id}/start-service|finish-service`（状态在预约表上，不另开前缀）。
 *
 * 未派单（CONFIRMED 且无执行人）在这里是**只读**的：派人是店长/前台的活
 * （`appointment:assign`），本页不给服务人员留一个改自己归属的入口。
 */

type Filter = 'TODO' | 'SERVING' | 'COMPLETED'

export default function TasksPage() {
  const { push } = useToast()
  const ctx = useSyncExternalStore(storeContext.subscribe, storeContext.get)
  // 消息深链（ADR-052）：/tasks?openId=预约id —— 那一单可能不在今天的列表里，按日历前向搜索定位后自动展开
  const [deepLinkId, setDeepLinkId] = useState<string | null>(() => new URLSearchParams(window.location.search).get('openId'))
  const [date, setDate] = useState(taskTodayISO())
  const [filter, setFilter] = useState<Filter>('TODO')
  const [rows, setRows] = useState<TaskRow[]>([])
  const [loading, setLoading] = useState(true)
  const [err, setErr] = useState('')
  const [busyId, setBusyId] = useState<string | null>(null)
  const [openId, setOpenId] = useState<string | null>(null)
  const [items, setItems] = useState<string[]>([])
  const [remark, setRemark] = useState('')

  const statusParam = filter === 'COMPLETED' ? 'COMPLETED' : filter === 'SERVING' ? 'SERVING' : undefined
  const load = useCallback(async () => {
    setLoading(true)
    setErr('')
    const requestStore = storeContext.currentStoreId()
    try {
      const v = await tasksApi.mine({ date, status: statusParam })
      // 任务页与导航角标共享同一份 todoTotal；仅在当前门店、且查询的是“今天”时回写，
      // 防止旧门店/历史日期的响应覆盖角标。
      if (requestStore && requestStore === storeContext.currentStoreId()) {
        applyTaskBadgeSnapshot(requestStore, v.date, v.todoTotal)
      }
      let list = v.list ?? []
      // TODO = 还没开始的（已确认待指派 / 待服务）；后端一次只收一类，所以这一档在前端合
      if (filter === 'TODO') list = list.filter((r) => r.status === 'CONFIRMED' || r.status === 'WAIT_SERVICE')
      setRows(list)
    } catch (e) {
      setErr(e instanceof BizError ? `${e.code} ${e.message}` : '加载失败')
      setRows([])
    } finally {
      setLoading(false)
    }
  }, [date, statusParam, filter])

  useEffect(() => {
    void load()
  }, [load, ctx.storeId])

  // 消息深链定位（ADR-052）：那一单可能不在今天的列表里（甚至已完成）——按日历前向搜索 7 天，
  // 找到即切日期/页签并展开；找不见给一句诚实提示（可能已完成落在历史、或已取消）。
  useEffect(() => {
    if (!deepLinkId) return
    let cancelled = false
    ;(async () => {
      const offsetDate = (off: number) => {
        const d = new Date()
        d.setDate(d.getDate() + off)
        return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
      }
      for (const off of [0, 1, 2, 3, 4, 5, 6]) {
        const d = offsetDate(off)
        try {
          const v = await tasksApi.mine({ date: d })
          const hit = (v.list ?? []).find((r) => r.appointmentId === deepLinkId)
          if (cancelled) return
          if (hit) {
            setDate(d)
            setFilter(hit.status === 'COMPLETED' ? 'COMPLETED' : hit.status === 'SERVING' ? 'SERVING' : 'TODO')
            setOpenId(deepLinkId)
            setDeepLinkId(null)
            return
          }
        } catch {
          // 单日查询失败不中断搜索（网络抖动时继续往后找）
        }
        if (cancelled) return
      }
      for (const off of [6, 5, 4, 3, 2, 1, 0]) {
        const d = offsetDate(off)
        try {
          const v = await tasksApi.mine({ date: d, status: 'COMPLETED' })
          const hit = (v.list ?? []).find((r) => r.appointmentId === deepLinkId)
          if (cancelled) return
          if (hit) {
            setDate(d)
            setFilter('COMPLETED')
            setOpenId(deepLinkId)
            setDeepLinkId(null)
            return
          }
        } catch {
          if (cancelled) return
        }
      }
      if (!cancelled) {
        push('未能在未来 7 天的任务里定位该预约：它可能已完成（翻「已完成」页签）或已取消', 'info')
        setDeepLinkId(null)
      }
    })()
    return () => {
      cancelled = true
    }
  }, [deepLinkId, push])

  const run = async (row: TaskRow, label: string, call: () => Promise<{ changed: boolean; status: string }>) => {
    if (busyId) return
    setBusyId(row.appointmentId)
    const who = row.petName ?? row.appointmentNo ?? '该单'
    try {
      const r = await call()
      push(r.changed ? `${who}：${label} → ${r.status}` : `${who}：该操作此前已完成（本次未改变状态）`, 'success')
      setOpenId(null)
      setItems([])
      setRemark('')
      await load()
    } catch (e) {
      push(e instanceof BizError ? e.message : `${label}失败，请稍后重试`, 'error')
    } finally {
      setBusyId(null)
    }
  }

  const toggleItem = (code: string) =>
    setItems((cur) => (cur.includes(code) ? cur.filter((c) => c !== code) : [...cur, code]))

  return (
    <Layout active="tasks">
      <div className="flex flex-col gap-4">
        <Card title="任务范围">
          <div className="flex flex-wrap items-center gap-3">
            <h1 className="text-[22px] font-black text-ink">我的任务</h1>
            <input
              type="date"
              value={date}
              onChange={(e) => setDate(e.target.value)}
              aria-label="任务日期"
              className="h-9 rounded-xl bg-white px-2 text-sm text-ink ring-1 ring-line focus:outline-none focus:ring-blue"
            />
            <div className="flex gap-1.5">
              {(['TODO', 'SERVING', 'COMPLETED'] as Filter[]).map((f) => (
                <button
                  key={f}
                  type="button"
                  aria-pressed={filter === f}
                  onClick={() => setFilter(f)}
                  className={cx(
                    'h-8 rounded-full px-3.5 text-xs font-semibold transition',
                    filter === f ? 'bg-blue text-white' : 'bg-warmgray text-ink/70 hover:text-blue',
                  )}
                >
                  {f === 'TODO' ? '待开始' : f === 'SERVING' ? '服务中' : '已完成'}
                </button>
              ))}
            </div>
            <button
              type="button"
              onClick={() => void load()}
              className="ml-auto h-8 rounded-full bg-white px-3 text-xs font-semibold text-blue ring-1 ring-line"
            >
              刷新
            </button>
          </div>
          <p className="mt-2 text-[11px] leading-relaxed text-muted">
            任务范围由服务端按登录身份收窄（只从 token 取员工 id，不接受参数指定他人）。
            当前门店上下文={ctx.storeId ?? '未选择'}；未派单的单子在这里只读——派人是店长/前台的活。
          </p>
        </Card>

        {err && (
          <Card title="加载失败">
            <p className="text-sm text-rose-600">{err}</p>
            <p className="mt-1 text-xs text-muted">
              若提示 10003，说明该账号在本店没有员工身份（遛狗师/美容师/医生/护理员才有个人任务）。
            </p>
          </Card>
        )}

        {loading && !err && <Card title="加载中"><p className="py-8 text-center text-sm text-muted">正在读取本人任务…</p></Card>}

        {!loading && !err && rows.length === 0 && (
          <Card title="今日无任务">
            <p className="py-10 text-center text-sm text-muted">
              {date} 没有{filter === 'TODO' ? '待开始' : filter === 'SERVING' ? '服务中' : '已完成'}的任务
              （换日期或筛选看看；当天若只有托管单，那些走「托管服务」页办理入住/离店）
            </p>
          </Card>
        )}

        {!loading &&
          rows.map((r) => {
            const open = openId === r.appointmentId
            const label = TASK_STATUS_LABEL[r.status as keyof typeof TASK_STATUS_LABEL] ?? r.status
            return (
              <Card key={r.appointmentId} title={`${r.petName ?? '宠物档案已删除'} · ${label}`}>
                <div className="flex items-start gap-3">
                  <span className="grid h-11 w-11 shrink-0 place-items-center rounded-xl bg-blue-mist text-blue ring-1 ring-line">
                    <IconPaw className="h-5 w-5" />
                  </span>
                  <div className="min-w-0 flex-1">
                    <p className="flex flex-wrap items-center gap-2 text-base font-black text-ink">
                      <span className="truncate">{r.petName ?? '宠物档案已删除'}</span>
                      <span className="rounded-full bg-warmgray px-2 py-0.5 text-[11px] font-semibold text-muted">
                        {label}
                      </span>
                      {isFutureDate(r.serviceDate) && r.status !== 'COMPLETED' && (
                        <span className="rounded-full px-2 py-0.5 text-[11px] font-semibold" style={{ background: TONE.orange.bg, color: TONE.orange.fg }}>
                          未到日期
                        </span>
                      )}
                    </p>
                    <p className="mt-1 truncate text-sm text-muted">
                      {r.serviceName ?? '服务名未记录'}
                      {r.serviceCode ? `（${r.serviceCode}）` : ''} · {r.startTime ?? '时间待定'}
                      {r.endTime ? `–${r.endTime}` : ''} ·{' '}
                      {/* fenToYuanText 自带「¥」前缀，这里不再加一层（曾渲染成 ¥¥60.00） */}
                      {r.price == null ? '价格未记录' : fenToYuanText(r.price)}
                    </p>
                    <p className="mt-0.5 text-[11px] text-muted">
                      预约号 {r.appointmentNo ?? '—'} · 日期 {r.serviceDate}
                      {isFutureDate(r.serviceDate) && r.status !== 'COMPLETED' && (
                        <span className="ml-1.5 text-[#8A6116]">· 到当天可开始服务</span>
                      )}
                    </p>
                  </div>
                  <div className="flex shrink-0 flex-col items-end gap-2">
                    {r.status === 'CONFIRMED' || r.status === 'WAIT_SERVICE' ? (
                      <button
                        type="button"
                        disabled={busyId !== null}
                        onClick={() => void run(r, '开始服务', () => bathingApi.startService(r.appointmentId))}
                        className="flex h-9 items-center gap-1.5 rounded-full bg-blue px-3.5 text-xs font-semibold text-white disabled:opacity-60"
                      >
                        <IconClock className="h-3.5 w-3.5" />
                        {busyId === r.appointmentId ? '提交中…' : '开始服务'}
                      </button>
                    ) : r.status === 'SERVING' ? (
                      <button
                        type="button"
                        disabled={busyId !== null}
                        onClick={() =>
                          void run(r, '完成服务', () =>
                            bathingApi.finishService(r.appointmentId, {
                              serviceItems: open ? items : [],
                              remark: remark.trim() || undefined,
                            }))
                        }
                        className="flex h-9 items-center gap-1.5 rounded-full bg-emerald-600 px-3.5 text-xs font-semibold text-white disabled:opacity-60"
                      >
                        <IconCheckCircle className="h-3.5 w-3.5" />
                        {busyId === r.appointmentId ? '提交中…' : '完成并提交记录'}
                      </button>
                    ) : null}
                    {r.status === 'SERVING' && (
                      <button
                        type="button"
                        onClick={() => setOpenId(open ? null : r.appointmentId)}
                        className="text-[11px] font-semibold text-blue"
                      >
                        {open ? '收起执行项' : '填写执行项/备注'}
                      </button>
                    )}
                  </div>
                </div>

                {open && (
                  <div className="mt-3 border-t border-line pt-3">
                    <div className="flex flex-wrap gap-2">
                      {SERVICE_ITEM_OPTIONS.map((o) => {
                        const on = items.includes(o.code)
                        return (
                          <button
                            key={o.code}
                            type="button"
                            aria-pressed={on}
                            onClick={() => toggleItem(o.code)}
                            className={cx(
                              'h-8 rounded-full px-3.5 text-xs font-semibold transition',
                              on ? 'bg-blue text-white' : 'bg-white text-ink ring-1 ring-line hover:text-blue',
                            )}
                          >
                            {o.label}
                          </button>
                        )
                      })}
                    </div>
                    <textarea
                      value={remark}
                      onChange={(e) => setRemark(e.target.value)}
                      rows={2}
                      maxLength={200}
                      placeholder="备注（可选，≤200 字）"
                      className="mt-3 w-full rounded-xl bg-white px-3 py-2 text-sm text-ink ring-1 ring-line placeholder:text-muted focus:outline-none focus:ring-blue"
                    />
                    <p className="mt-2 text-[11px] leading-relaxed text-muted">
                      执行项/备注落在 <code className="font-mono">ful_bath_record</code>（洗澡·美容）或
                      <code className="font-mono"> ful_walk_record</code>（遛狗，只有备注）；
                      医疗类库里没有对应记录表，完成后只推进预约状态，不会编一条记录出来。
                    </p>
                  </div>
                )}
              </Card>
            )
          })}
      </div>
    </Layout>
  )
}
