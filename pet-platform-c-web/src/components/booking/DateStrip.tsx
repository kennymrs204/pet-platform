import { dateText, weekdayOf } from '../../lib/dateText'
import { cx } from '../../lib/cx'

/**
 * 日期条（预约页与改期面板共用）。`days` 由调用方给（预约页=未来 14 天，改期页=从今起 30 天窗口内）。
 * 只显示日/周几，今天额外标「今天」。
 */
export default function DateStrip({
  days,
  value,
  onChange,
  today,
}: {
  days: string[]
  value: string
  onChange: (iso: string) => void
  today: string
}) {
  return (
    <div className="flex gap-2 overflow-x-auto pb-1">
      {days.map((d) => {
        const on = d === value
        return (
          <button
            key={d}
            type="button"
            aria-pressed={on}
            onClick={() => onChange(d)}
            className={cx(
              'shrink-0 rounded-2xl px-3 py-2 text-center text-[11px] font-semibold transition',
              on ? 'bg-btn-gradient text-white shadow-btn' : 'bg-white/85 text-navy ring-1 ring-navy/5 hover:bg-white',
            )}
          >
            {d === today ? '今天' : dateText(d).split(' ')[0]}
            <span className={cx('block text-[10px] font-normal', on ? 'text-white/75' : 'text-muted')}>{weekdayOf(d)}</span>
          </button>
        )
      })}
    </div>
  )
}
