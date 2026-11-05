import { PetVO } from '../../api/pet'
import { cx } from '../../lib/cx'
import { SIZE_LABEL, SPECIES_LABEL } from '../../lib/petText'
import { IconPlus } from '../ui/icons'
import PetAvatar from './PetAvatar'

/**
 * 宠物选择器（主页宠物切换卡与预约页共用同一交互与文案口径）。
 * 体型显示中文：后端只回 SMALL/MEDIUM/LARGE 枚举码。
 */
export default function PetPicker({
  pets,
  value,
  onChange,
  onAdd,
}: {
  pets: PetVO[]
  value: string | null
  onChange: (id: string) => void
  onAdd: () => void
}) {
  return (
    <div className="flex gap-2 overflow-x-auto pb-1">
      {pets.map((p) => {
        const on = p.id === value
        return (
          <button
            key={p.id}
            type="button"
            aria-pressed={on}
            onClick={() => onChange(p.id)}
            className={cx(
              'flex shrink-0 items-center gap-2 rounded-2xl px-3 py-2 text-xs font-semibold transition',
              on ? 'bg-btn-gradient text-white shadow-btn' : 'bg-white/85 text-navy ring-1 ring-navy/5 hover:bg-white',
            )}
          >
            <PetAvatar pet={p} className="h-8 w-8" />
            <span className="text-left">
              {p.name}
              <span className={cx('block text-[10px] font-normal', on ? 'text-white/75' : 'text-muted')}>
                {SPECIES_LABEL[p.species]} · {p.sizeCategory ? SIZE_LABEL[p.sizeCategory] : '体型未判定'}
              </span>
            </span>
          </button>
        )
      })}
      <button
        type="button"
        onClick={onAdd}
        className="flex shrink-0 items-center gap-1 rounded-2xl border border-dashed border-navy/20 px-3 text-[11px] font-semibold text-navy/70"
      >
        <IconPlus className="h-3.5 w-3.5" /> 添加
      </button>
    </div>
  )
}
