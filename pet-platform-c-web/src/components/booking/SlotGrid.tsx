import { AvailabilitySlot } from '../../api/appointment'
import { cx } from '../../lib/cx'

/** 时段网格（预约页与改期面板共用）。槽位由服务端算，前端不生成、不外推。 */
export default function SlotGrid({
  slots,
  value,
  onChange,
}: {
  slots: AvailabilitySlot[]
  value: string | null
  onChange: (start: string) => void
}) {
  return (
    <div className="grid grid-cols-3 gap-2">
      {slots.map((s) => {
        const on = s.start === value
        return (
          <button
            key={s.start}
            type="button"
            aria-pressed={on}
            onClick={() => onChange(s.start)}
            className={cx(
              'rounded-2xl py-2.5 text-center text-xs font-semibold transition',
              on ? 'bg-btn-gradient text-white shadow-btn' : 'bg-white/85 text-navy ring-1 ring-navy/5 hover:bg-white',
            )}
          >
            {s.start}
            <span className={cx('block text-[10px] font-normal', on ? 'text-white/70' : 'text-muted')}>至 {s.end}</span>
          </button>
        )
      })}
    </div>
  )
}
