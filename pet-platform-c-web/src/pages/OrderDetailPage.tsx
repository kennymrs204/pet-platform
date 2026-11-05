import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { BizCode, BizError } from '../api/types'
import { AppointmentSelfView, appointmentApi, type WalkRecordPhotos } from '../api/appointment'
import { fenToYuan } from '../api/catalog'
import { OrderView, ORDER_STATUS_TEXT, orderApi } from '../api/order'
import { REFUND_STATE_TEXT, refundApi, type RefundProgress } from '../api/refund'
import { PetVO, petApi } from '../api/pet'
import { chatApi } from '../api/chat'
import { useBizFail } from '../auth/useBizFail'
import PageHeader from '../components/layout/PageHeader'
import PaySheet from '../components/order/PaySheet'
import CancelDialog from '../components/order/CancelDialog'
import ReschedulePanel from '../components/order/ReschedulePanel'
import PetAvatar from '../components/pet/PetAvatar'
import { IconChat, IconCheck } from '../components/ui/icons'
import { useToast } from '../components/ui/Toast'
import { cx } from '../lib/cx'
import { countdownText, dateText, durationText } from '../lib/dateText'
import { APPT_STATUS_TEXT, apptStatusTone, apptWhenText, canCancel } from '../lib/orderBoard'
import { usePublicStores } from '../lib/usePublicStores'
import { formatDistanceM, formatDurationSec, trackSummaryText, trackSvgPath } from '../lib/walkTrack'
import { SIZE_LABEL, SPECIES_LABEL } from '../lib/petText'

/**
 * 订单详情（GET /customer/appointments/{id} + 本地 join 订单与宠物）。
 * 预约 id 即路由 id；订单要靠 appointmentId 在列表里反查（AppointmentSelfView 不含 orderId）。
 * 遛狗已完成时另读 GET /customer/appointments/{id}/walk-record 渲染「服务报告」；
 * 报告独立读取，失败只影响报告区，不影响订单/金额等其他信息。
 */
export default function OrderDetailPage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const { push } = useToast()
  const fail = useBizFail()

  const [appt, setAppt] = useState<AppointmentSelfView | null>(null)
  const [order, setOrder] = useState<OrderView | null>(null)
  const [pet, setPet] = useState<PetVO | null>(null)
  const [paying, setPaying] = useState(false)
  const [rescheduling, setRescheduling] = useState(false)
  const [cancelOpen, setCancelOpen] = useState(false)
  const [cancelResult, setCancelResult] = useState<{ state: string; amount: string | number | null } | null>(null)
  const [refund, setRefund] = useState<RefundProgress | null>(null)
  /** 遛狗服务报告（仅服务已完成时读；非遛狗/无记录时后端回 hasRecord=false，前端显示自然空态） */
  const [walkPhotos, setWalkPhotos] = useState<WalkRecordPhotos | null>(null)
  /** 报告模块读取失败：只标记报告区，订单/金额等其他信息照常展示 */
  const [walkReportFailed, setWalkReportFailed] = useState(false)
  const [chatOpening, setChatOpening] = useState(false)
  const [, setTick] = useState(0)
  const { nameOf } = usePublicStores()

  const load = useCallback(
    (signal: AbortSignal) => {
      // 换单时先收起上一单的报告，避免旧数据在新单加载期间短暂串台
      setWalkPhotos(null)
      setWalkReportFailed(false)
      appointmentApi
        .detail(id)
        .then(async (a) => {
          if (signal.aborted) return
          setAppt(a)
          // 报告模块独立读取：失败或变慢都不阻塞订单、宠物、退款等其他信息；
          // 非遛狗预约读回来是 hasRecord=false，属正常空态。
          if (a.status === 'COMPLETED') {
            appointmentApi.walkRecord(String(a.id)).then(
              (data) => {
                if (!signal.aborted) setWalkPhotos(data)
              },
              () => {
                if (!signal.aborted) setWalkReportFailed(true)
              },
            )
          }
          const [orders, pets, refundRes] = await Promise.all([
            orderApi.list(undefined, 100).catch(() => null),
            petApi.detail(a.petId).catch(() => null),
            // 退款进度回读口径（ADR-043）：
            //  CANCEL_REQUESTED=审核中、CANCELLED=已落定，两种都要读；
            //  CONFIRMED 也要读——被拒后预约会恢复成 CONFIRMED，若不读，"退款申请未通过"就只剩消息里那一行，
            //     订单页反而看不出发生过什么。没有退款单时后端回 data=null，多读一次不骗人。
            a.status === 'CANCELLED' || a.status === 'CANCEL_REQUESTED' || a.status === 'CONFIRMED'
              ? refundApi.progress(String(a.id), signal).catch(() => null)
              : Promise.resolve(null),
          ])
          if (signal.aborted) return
          setPet(pets)
          setRefund(refundRes)
          setOrder(orders?.list.find((o) => String(o.appointmentId) === String(a.id)) ?? null)
        })
        .catch((e) => {
          if (signal.aborted) return
          if (e instanceof BizError && e.code === BizCode.NOT_FOUND) {
            push('找不到这条预约，可能已被删除', 'error')
            navigate('/orders', { replace: true })
            return
          }
          fail(e)
        })
    },
    [id, fail, navigate, push],
  )

  useEffect(() => {
    if (!id) return
    const ac = new AbortController()
    load(ac.signal)
    return () => ac.abort()
  }, [id, load])

  /**
   * 「联系门店」：业务 id 全部来自真实详情数据——storeId=预约所属门店、
   * bizModule=appointment、bizId=**预约 id**（本路由 id；订单 id 与预约 id 不同，不可混用）。
   * 会话获取由后端 get-or-create，前端不造 id；entry 回显编码进 URL 供刷新后恢复本次上下文
   * （仅展示/导航用途，服务端鉴权不依赖它）。失败如实报错，不伪造跳转。
   */
  const openChat = async () => {
    if (chatOpening || !appt) return
    setChatOpening(true)
    try {
      const res = await chatApi.open({
        storeId: appt.storeId,
        bizModule: 'appointment',
        bizId: appt.id,
      })
      const q = new URLSearchParams()
      if (res.entryBizModule) q.set('biz', res.entryBizModule)
      if (res.entryBizId) q.set('bizId', String(res.entryBizId))
      const qs = q.toString()
      navigate(`/chat/${res.conversation.id}${qs ? `?${qs}` : ''}`)
    } catch (e) {
      fail(e)
    } finally {
      setChatOpening(false)
    }
  }

  const payable = appt?.status === 'PENDING_PAYMENT' && !!order
  useEffect(() => {
    if (!payable) return
    const t = window.setInterval(() => setTick((n) => n + 1), 1000)
    return () => window.clearInterval(t)
  }, [payable])

  const left = useMemo(() => (payable ? countdownText(appt?.expireAt) : null), [payable, appt?.expireAt])

  if (!appt) {
    return (
      <main className="min-h-[100dvh] bg-brand-gradient">
        <div className="mx-auto max-w-md px-4 pb-10">
          <PageHeader title="订单详情" fallback="/orders" />
          <div className="animate-pulse space-y-4 pt-4" aria-busy="true">
            <div className="h-28 rounded-3xl bg-white/60" />
            <div className="h-48 rounded-3xl bg-white/60" />
          </div>
        </div>
      </main>
    )
  }

  const multiDay = !!appt.boardingCheckinDate
  /** ADR-025：MULTI_DAY 订单的 item_snapshot 带 days（晚数）与 price（日单价）；price_snapshot 恒为总额。 */
  const billingDays = order?.itemSnapshot?.days ?? null
  const billingUnit = order?.itemSnapshot?.price ?? null

  return (
    <main className="min-h-[100dvh] bg-brand-gradient">
      <div className="mx-auto max-w-md px-4 pb-12">
        <PageHeader title="订单详情" fallback="/orders" />

        <section className="animate-fade-up rounded-3xl bg-white/90 p-4 shadow-soft ring-1 ring-navy/5">
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              <h1 className="truncate text-base font-bold text-ink">{appt.serviceNameSnapshot}</h1>
              <p className="mt-1 text-[11px] text-muted">{apptWhenText(appt)}</p>
            </div>
            <span className={cx('shrink-0 rounded-full px-2.5 py-1 text-[11px] font-semibold', apptStatusTone(appt.status))}>
              {APPT_STATUS_TEXT[appt.status] ?? appt.status}
            </span>
          </div>

          {payable && (
            <>
              <p className="mt-3 rounded-2xl bg-gold/10 px-3 py-2 text-[11px] text-navy">
                {left ? `请在 ${left} 内完成支付，超时后自动取消并释放资源` : '支付已超时，稍后会被自动取消'}
              </p>
              <button
                type="button"
                onClick={() => setPaying(true)}
                className="mt-3 h-11 w-full rounded-2xl bg-btn-gradient text-sm font-semibold text-white shadow-btn active:scale-[0.99]"
              >
                去支付 ¥{fenToYuan(order?.amount ?? appt.priceSnapshot)}
              </button>
              <button
                type="button"
                onClick={() => setRescheduling((v) => !v)}
                className="mt-2 h-10 w-full rounded-2xl bg-warmgray text-xs font-semibold text-navy/80"
              >
                {rescheduling ? '收起改期' : '改期（换日期/时段，金额不变）'}
              </button>
            </>
          )}

          <Timeline status={appt.status} paidAt={order?.paidAt ?? null} />
        </section>

        {rescheduling && appt.status === 'PENDING_PAYMENT' && (
          <ReschedulePanel
            appt={appt}
            onError={fail}
            onClose={() => setRescheduling(false)}
            onModified={(next) => {
              setAppt(next)
              setRescheduling(false)
              load(new AbortController().signal)
            }}
          />
        )}

        {pet && (
          <button
            type="button"
            onClick={() => navigate(`/pets/${pet.id}`)}
            className="animate-fade-up mt-4 flex w-full items-center gap-3 rounded-3xl bg-white/90 p-4 text-left shadow-soft ring-1 ring-navy/5"
            style={{ animationDelay: '70ms' }}
          >
            <PetAvatar pet={pet} className="h-12 w-12" />
            <span className="min-w-0 flex-1">
              <span className="block truncate text-sm font-bold text-ink">{pet.name}</span>
              <span className="mt-0.5 block truncate text-[11px] text-muted">
                {SPECIES_LABEL[pet.species]}
                {pet.breed ? ` · ${pet.breed}` : ''}
                {pet.sizeCategory ? ` · ${SIZE_LABEL[pet.sizeCategory]}` : ''}
              </span>
            </span>
            <span className="shrink-0 text-[11px] font-semibold text-navy">查看档案</span>
          </button>
        )}
        {!pet && (
          <p className="animate-fade-up mt-4 rounded-2xl bg-warmgray px-3 py-2.5 text-[11px] text-muted" style={{ animationDelay: '70ms' }}>
            这只宠物的档案已不可见（被删除或归属变更），订单信息仍完整保留。
          </p>
        )}

        <section className="animate-fade-up mt-4 rounded-3xl bg-white/90 p-4 shadow-soft ring-1 ring-navy/5" style={{ animationDelay: '140ms' }}>
          <h2 className="text-sm font-bold text-ink">服务信息</h2>
          <dl className="mt-3 space-y-2.5 text-xs">
            <Line k="门店" v={nameOf(String(appt.storeId))} />
            <Line k="预约时长（计划）" v={durationText(appt.durationSnapshot)} />
            <Line k={multiDay ? '托管区间' : '到店时间'} v={apptWhenText(appt)} />
            <Line k="服务人员" v={appt.employeeId ? `员工 ${appt.employeeId}` : '由门店安排'} />
            <Line k="备注" v={appt.remark || '无'} />
          </dl>
          <button
            type="button"
            onClick={() => void openChat()}
            disabled={chatOpening}
            aria-label={`联系门店：${nameOf(String(appt.storeId))}`}
            className="mt-3 flex h-10 w-full items-center justify-center gap-1.5 rounded-2xl bg-warmgray text-xs font-semibold text-navy/80 disabled:opacity-60"
          >
            <IconChat className="h-4 w-4" />
            {chatOpening ? '正在打开…' : '联系门店'}
          </button>
        </section>

        {walkPhotos?.hasRecord && (
          <section className="animate-fade-up mt-4 rounded-3xl bg-white/90 p-4 shadow-soft ring-1 ring-navy/5" style={{ animationDelay: '210ms' }}>
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <h2 className="text-sm font-bold text-ink">服务报告</h2>
                <p className="mt-1 text-[11px] leading-relaxed text-muted">
                  仅展示服务端已保存的履约记录、GPS 轨迹与照片；未采集或读取不到的数据不补造。
                </p>
              </div>
              <span className={cx('shrink-0 rounded-full px-2.5 py-1 text-[11px] font-semibold', apptStatusTone(appt.status))}>
                {APPT_STATUS_TEXT[appt.status] ?? appt.status}
              </span>
            </div>

            <dl className="mt-3 space-y-2.5 text-xs">
              <Line k="服务状态" v={APPT_STATUS_TEXT[appt.status] ?? appt.status} />
              <Line
                k="完成时间"
                v={walkPhotos.finishedAt ? walkPhotos.finishedAt.replace('T', ' ').slice(0, 16) : '未记录'}
              />
              <Line
                k="服务实际时长（起止实算）"
                v={walkPhotos.durationMin != null ? durationText(walkPhotos.durationMin) : '未记录'}
              />
              <Line
                k="GPS 记录时长"
                v={walkPhotos.track ? formatDurationSec(walkPhotos.track.durationSec) : '未采集'}
              />
              <Line
                k="GPS 距离"
                v={walkPhotos.track ? formatDistanceM(walkPhotos.track.distanceM) : '未采集'}
              />
            </dl>
            <p className="mt-2 rounded-2xl bg-warmgray px-3 py-2 text-[11px] leading-relaxed text-muted">
              服务实际时长按开始/完成时间实算；GPS 记录时长只统计有效定位点且扣除暂停，两者口径不同。
            </p>

            <div className="mt-3 border-t border-navy/5 pt-3">
              <p className="text-xs font-bold text-ink">遛狗轨迹</p>
              {walkPhotos.track ? (
                <div className="mt-2 flex flex-wrap items-start gap-3">
                  <WalkTrackSparkline polyline={walkPhotos.track.polyline} />
                  <div className="min-w-0 flex-1 text-[11px] leading-relaxed text-muted">
                    <p className="text-xs font-semibold text-ink">{trackSummaryText(walkPhotos.track)}</p>
                    <p className="mt-0.5">
                      采集区间 {dateText(walkPhotos.track.startedAt)} → {dateText(walkPhotos.track.endedAt)}
                      {walkPhotos.track.pausedSec > 0
                        ? ` · 暂停 ${formatDurationSec(walkPhotos.track.pausedSec)}`
                        : ''}
                    </p>
                    <p className="mt-0.5">
                      距离为门店记录的 GPS 轨迹（已剔除异常跳点），不是预约时长或估算值。
                    </p>
                  </div>
                </div>
              ) : (
                <p className="mt-1.5 text-[11px] leading-relaxed text-muted">
                  本次未记录 GPS 轨迹（门店未开启轨迹记录时不补造路线）。
                </p>
              )}
            </div>

            <div className="mt-3 border-t border-navy/5 pt-3">
              <p className="flex flex-wrap items-center gap-1.5 text-xs font-bold text-ink">
                遛狗照片
                <span className="font-normal text-muted">
                  {walkPhotos.photos.length > 0 ? `${walkPhotos.photos.length} 张` : '暂无'}
                </span>
              </p>
              {walkPhotos.photos.length === 0 ? (
                <p className="mt-2 text-[11px] leading-relaxed text-muted">
                  本次遛狗还没有照片（门店未上传时不补占位图）。
                </p>
              ) : walkPhotos.photoUrls.length === 0 ? (
                <p className="mt-2 text-[11px] leading-relaxed text-gold-dark">
                  已有 {walkPhotos.photos.length} 张照片，但当前取不到读取地址（对象存储未启用或签名失败）；
                  记录本身不受影响，可稍后刷新重试。
                </p>
              ) : (
                <>
                  <ul className="mt-2 flex flex-wrap gap-2">
                    {walkPhotos.photoUrls.map((url, i) => (
                      <li key={`${url}-${i}`}>
                        <WalkPhotoThumb url={url} index={i} />
                      </li>
                    ))}
                  </ul>
                  {walkPhotos.photoUrls.length < walkPhotos.photos.length && (
                    <p className="mt-2 text-[11px] leading-relaxed text-gold-dark">
                      另有 {walkPhotos.photos.length - walkPhotos.photoUrls.length} 张照片暂不可读（签名失败或对象已失效）。
                    </p>
                  )}
                </>
              )}
            </div>
          </section>
        )}

        {walkReportFailed && appt.status === 'COMPLETED' && (
          <section className="animate-fade-up mt-4 rounded-3xl bg-white/90 p-4 shadow-soft ring-1 ring-navy/5" style={{ animationDelay: '210ms' }}>
            <h2 className="text-sm font-bold text-ink">服务报告</h2>
            <p className="mt-2 text-[11px] leading-relaxed text-muted">
              本次服务报告读取失败，暂时无法展示履约记录、GPS 轨迹与照片；订单与金额信息不受影响。
            </p>
            <button
              type="button"
              onClick={() => load(new AbortController().signal)}
              className="mt-3 h-9 w-full rounded-2xl bg-warmgray text-xs font-semibold text-navy/80"
            >
              重试
            </button>
          </section>
        )}

        <section className="animate-fade-up mt-4 rounded-3xl bg-white/90 p-4 shadow-soft ring-1 ring-navy/5" style={{ animationDelay: '210ms' }}>
          <h2 className="text-sm font-bold text-ink">金额与订单</h2>
          <dl className="mt-3 space-y-2.5 text-xs">
            {billingDays && billingDays > 1 ? (
              <Line k="计费" v={`${billingDays} 晚 × ¥${fenToYuan(billingUnit)}/天`} />
            ) : (
              <Line k="服务单价" v={`¥${fenToYuan(billingUnit ?? appt.priceSnapshot)}`} />
            )}
            <Line k="优惠" v="¥0（折扣字段后端预留恒 0）" />
            <div className="flex items-center justify-between border-t border-navy/5 pt-2.5">
              <dt className="text-muted">实付/应付</dt>
              <dd className="font-mono text-sm font-bold text-navy">¥{fenToYuan(order?.amount ?? appt.priceSnapshot)}</dd>
            </div>
            <Line k="预约号" v={appt.appointmentNo} mono />
            <Line k="订单号" v={order?.orderNo ?? '未生成'} mono />
            <Line k="订单状态" v={order ? ORDER_STATUS_TEXT[order.status] : '—'} />
            {order?.paymentMethod && <Line k="支付方式" v={order.paymentMethod === 'ONLINE' ? '线上' : order.paymentMethod} />}
            <Line k="创建时间" v={appt.createdAt?.replace('T', ' ').slice(0, 16) ?? '—'} />
          </dl>
        </section>

        {multiDay && (
          <div className="animate-fade-up mt-4 rounded-3xl bg-white/90 p-4 shadow-soft ring-1 ring-navy/5" style={{ animationDelay: '280ms' }}>
            <p className="text-[11px] leading-relaxed text-navy/75">
              托管日报按「住单」组织。后端尚无「按预约查住单」的端点，所以这里还不能一键直达那一条；
              可以从「我的托管」列表进入查看进度与每日照护记录。
            </p>
            <button
              type="button"
              onClick={() => navigate('/boarding')}
              className="mt-3 h-10 w-full rounded-2xl bg-warmgray text-xs font-semibold text-navy/80"
            >
              查看我的托管
            </button>
          </div>
        )}

        {refund ? (
          <div className="animate-fade-up mt-4 rounded-3xl bg-white/90 p-4 shadow-soft ring-1 ring-navy/5">
            <h2 className="text-sm font-bold text-ink">
              {REFUND_STATE_TEXT[refund.status] ?? `退款状态：${refund.status}`}
            </h2>
            <p className="mt-2 text-[11px] leading-relaxed text-muted">
              退款金额 ¥{fenToYuan(refund.amount)}
              {refund.status === 'APPLYING' && '，门店正在按取消政策审核；审核通过并退款成功后，预约才会取消、金额退回原支付渠道。'}
              {refund.status === 'SUCCESS' && `，已于 ${dateText(refund.refundedAt)} 退回原支付渠道，预约同时已取消。`}
              {refund.status === 'REJECTED' && (refund.rejectReason
                ? `，门店未通过本次申请：${refund.rejectReason}。预约已恢复可服务状态、订单仍然有效，如有疑问可联系门店。`
                : '，门店未通过本次申请：预约已恢复可服务状态、订单仍然有效。门店未登记拒绝理由，如有疑问可联系门店。')}
              <span className="mt-1 block text-[10px] text-muted/80">
                退款单号 {refund.refundNo} · 申请于 {dateText(refund.createdAt)} ·
                同一进度也会出现在顶栏「消息中心」
              </span>
            </p>
          </div>
        ) : cancelResult ? (
          <div className="animate-fade-up mt-4 rounded-3xl bg-white/90 p-4 shadow-soft ring-1 ring-navy/5">
            <h2 className="text-sm font-bold text-ink">
              {cancelResult.state === 'APPLYING' ? '取消申请审核中' : '已取消，未产生费用'}
            </h2>
            <p className="mt-2 text-[11px] leading-relaxed text-muted">
              {cancelResult.state === 'APPLYING'
                ? `您的取消申请已提交，退款金额 ¥${fenToYuan(cancelResult.amount)} 按门店取消政策计算，等待门店审核。审核通过并退款成功后预约才会取消；被拒绝则预约恢复可服务、订单仍然有效。`
                : '该预约未支付，订单已关闭并释放锁定的资源。'}
            </p>
          </div>
        ) : null}

        {canCancel(appt.status) && (
          <button
            type="button"
            onClick={() => setCancelOpen(true)}
            className="animate-fade-up mt-4 h-11 w-full rounded-2xl bg-white/70 text-xs font-semibold text-rose-500 ring-1 ring-rose-200/70"
          >
            {appt.status === 'CONFIRMED' ? '取消预约并申请退款' : '取消预约'}
          </button>
        )}

        <p className="mt-3 px-1 text-[11px] leading-relaxed text-muted">
          改期已接真实接口（ADR-026，仅未支付可改，金额不变）；已支付要换时间请走「取消并申请退款」后重新预约。
        </p>
      </div>

      {cancelOpen && (
        <CancelDialog
          appt={appt}
          storeName={nameOf(String(appt.storeId))}
          onClose={() => setCancelOpen(false)}
          onCancelled={(state, amount) => {
            setCancelResult({ state, amount })
            load(new AbortController().signal)
          }}
        />
      )}

      {paying && order && (
        <PaySheet
          order={order}
          onClose={() => setPaying(false)}
          onPaid={(paid) => {
            setOrder(paid)
            load(new AbortController().signal)
          }}
        />
      )}
    </main>
  )
}

const STEPS: { key: string; label: string; before: string[] }[] = [
  { key: 'created', label: '已创建预约', before: ['PENDING_PAYMENT', 'CONFIRMED', 'CANCEL_REQUESTED', 'WAIT_SERVICE', 'SERVING', 'COMPLETED'] },
  { key: 'paid', label: '已支付确认', before: ['CONFIRMED', 'CANCEL_REQUESTED', 'WAIT_SERVICE', 'SERVING', 'COMPLETED'] },
  { key: 'serving', label: '服务进行中', before: ['SERVING'] },
  { key: 'done', label: '已完成', before: ['COMPLETED'] },
]

function Timeline({ status, paidAt }: { status: string; paidAt: string | null }) {
  if (status === 'CANCEL_REQUESTED') {
    // 申请阶段绝不写"已取消"（ADR-043 §2）：预约还占着台位，取消要等门店批 + 退款成功才生效
    return (
      <p className="mt-3 rounded-2xl bg-gold/10 px-3 py-2.5 text-[11px] leading-relaxed text-gold-dark ring-1 ring-gold/30">
        该预约已支付，<b>取消申请审核中</b>——时段仍为你保留。门店审核通过并退款成功后才会真正取消；
        若被拒绝，预约自动恢复可服务状态。退款进度见下方卡片。
      </p>
    )
  }
  if (status === 'CANCELLED') {
    return (
      <p className="mt-3 rounded-2xl bg-warmgray px-3 py-2.5 text-[11px] text-muted">
        该预约已取消{paidAt ? '（取消前曾支付，退款进度见下方卡片）' : '（未支付，未产生费用）'}。
      </p>
    )
  }
  return (
    <ol className="mt-4 space-y-2.5 border-t border-navy/5 pt-4">
      {STEPS.map((s) => {
        const done = s.before.includes(status)
        return (
          <li key={s.key} className="flex items-center gap-2.5 text-xs">
            <span
              className={cx(
                'grid h-5 w-5 shrink-0 place-items-center rounded-full',
                done ? 'bg-emerald-500 text-white' : 'bg-warmgray text-transparent ring-1 ring-navy/5',
              )}
            >
              <IconCheck className="h-3 w-3" />
            </span>
            <span className={cx(done ? 'font-semibold text-ink' : 'text-muted')}>{s.label}</span>
            {s.key === 'paid' && done && paidAt && (
              <span className="ml-auto text-[11px] text-muted">{paidAt.replace('T', ' ').slice(5, 16)}</span>
            )}
          </li>
        )
      })}
    </ol>
  )
}

function Line({ k, v, mono }: { k: string; v: string; mono?: boolean }) {
  return (
    <div className="flex items-start justify-between gap-3">
      <dt className="shrink-0 text-muted">{k}</dt>
      <dd className={cx('min-w-0 break-all text-right font-semibold text-ink', mono && 'font-mono text-[11px] font-normal')}>{v}</dd>
    </div>
  )
}

/** 内联 SVG 示意路线（项目未接地图 SDK；少于 2 点显示空态，不画假路线）。 */
function WalkTrackSparkline({ polyline }: { polyline: [number, number][] | null }) {
  const d = trackSvgPath(polyline)
  if (!d) {
    return (
      <span className="grid h-12 w-28 shrink-0 place-items-center rounded-2xl bg-warmgray text-[10px] text-muted ring-1 ring-navy/5">
        无轨迹线
      </span>
    )
  }
  return (
    <svg
      viewBox="0 0 120 48"
      width={120}
      height={48}
      role="img"
      aria-label="轨迹示意路线"
      className="shrink-0 rounded-2xl bg-warmgray ring-1 ring-navy/5"
    >
      <path d={d} fill="none" stroke="#2E6BFF" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

/** 遛狗照片缩略图：地址是 600s 签名的临时 URL，过期/对象被删都会加载失败——回落占位，不影响其他信息。 */
function WalkPhotoThumb({ url, index }: { url: string; index: number }) {
  const [failed, setFailed] = useState(false)
  if (failed) {
    return (
      <span
        className="grid h-20 w-20 place-items-center rounded-2xl bg-warmgray text-[10px] text-muted ring-1 ring-navy/5"
        title="图片加载失败"
      >
        加载失败
      </span>
    )
  }
  return (
    <a href={url} target="_blank" rel="noreferrer" title={`第 ${index + 1} 张，点击查看原图`}>
      <img
        src={url}
        alt={`遛狗照片 ${index + 1}`}
        loading="lazy"
        onError={() => setFailed(true)}
        className="h-20 w-20 rounded-2xl object-cover ring-1 ring-navy/5"
      />
    </a>
  )
}
