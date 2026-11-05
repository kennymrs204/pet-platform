import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { PetVO, petApi } from '../api/pet'
import { tokenStore } from '../auth/tokenStore'
import { useBizFail } from '../auth/useBizFail'
import BottomNav from '../components/layout/BottomNav'
import PageHeader from '../components/layout/PageHeader'
import PetAvatar from '../components/pet/PetAvatar'
import { IconCalendar, IconChevronRight, IconPaw, IconPlus } from '../components/ui/icons'
import { GENDER_SYMBOL, SPECIES_LABEL, STATUS_TEXT, petSubtitle, petWeightText, statusBadgeTone } from '../lib/petText'
import { cx } from '../lib/cx'

/**
 * C 端「我的宠物」列表（GET /customer/pets，无分页——个人宠物量级小，契约即全量）。
 * 数据全部来自后端；本页不做任何演示回退，空态就是真的空。
 */
export default function PetsPage() {
  const navigate = useNavigate()
  const fail = useBizFail()
  const userId = tokenStore.getUserId()

  const [pets, setPets] = useState<PetVO[] | null>(null)

  const load = useCallback(
    (signal?: AbortSignal) => {
      petApi
        .list()
        .then((d) => setPets(Array.isArray(d) ? d : []))
        .catch((e) => {
          if (signal?.aborted) return
          setPets([])
          fail(e)
        })
    },
    [fail],
  )

  useEffect(() => {
    if (!userId) {
      navigate('/login', { replace: true })
      return
    }
    const ac = new AbortController()
    load(ac.signal)
    return () => ac.abort()
  }, [userId, navigate, load])

  return (
    <main className="min-h-[100dvh] bg-brand-gradient">
      <div className="mx-auto max-w-md px-4 pb-32">
        <PageHeader title="我的宠物" right={{ label: '添加宠物', onClick: () => navigate('/pets/new') }} />

        {pets === null ? (
          <div className="mt-2 space-y-3" aria-busy="true">
            <SkeletonPetCard />
            <SkeletonPetCard />
          </div>
        ) : pets.length === 0 ? (
          <EmptyPets onAdd={() => navigate('/pets/new')} />
        ) : (
          <>
            <p className="mt-1 text-xs text-muted">{pets.length} 只毛孩子，档案已同步到云端</p>
            <ul className="mt-3 space-y-3">
              {pets.map((p, i) => (
                <PetRow key={p.id} pet={p} delay={i * 70} onOpen={() => navigate(`/pets/${p.id}`)} />
              ))}
            </ul>
            <button
              type="button"
              onClick={() => navigate('/pets/new')}
              className="mt-3 flex w-full items-center justify-center gap-1.5 rounded-3xl border border-dashed border-navy/20 bg-white/60 py-4 text-sm font-semibold text-navy/80"
            >
              <IconPlus className="h-4 w-4" /> 添加新的毛孩子
            </button>
          </>
        )}
      </div>
      <BottomNav active="mine" />
    </main>
  )
}

function PetRow({ pet, delay, onOpen }: { pet: PetVO; delay: number; onOpen: () => void }) {
  return (
    <li className="animate-fade-up" style={{ animationDelay: `${delay}ms` }}>
      <button
        type="button"
        onClick={onOpen}
        className="w-full rounded-3xl bg-white/90 p-4 text-left shadow-soft ring-1 ring-navy/5 transition hover:bg-white"
      >
        <div className="flex items-start gap-3">
          <PetAvatar pet={pet} className="h-14 w-14" />
          <div className="min-w-0 flex-1">
            <p className="flex items-center gap-1.5">
              <span className="truncate text-[15px] font-bold text-ink">{pet.name}</span>
              {pet.gender && GENDER_SYMBOL[pet.gender] && (
                <span
                  className={cx(
                    'shrink-0 text-sm font-bold',
                    pet.gender === 'FEMALE' ? 'text-rose-400' : 'text-navy-soft',
                  )}
                >
                  {GENDER_SYMBOL[pet.gender]}
                </span>
              )}
              <IconChevronRight className="ml-auto h-4 w-4 shrink-0 text-muted" />
            </p>
            <p className="mt-0.5 truncate text-xs text-muted">{petSubtitle(pet)}</p>
            <div className="mt-2 flex flex-wrap items-center gap-1.5">
              <span className={cx('rounded-full px-2 py-0.5 text-[11px] font-semibold', statusBadgeTone(pet.status))}>
                {STATUS_TEXT[pet.status]}
              </span>
              <span className="rounded-full bg-ivory px-2 py-0.5 text-[11px] font-medium text-gold-dark ring-1 ring-gold/30">
                {SPECIES_LABEL[pet.species]}
              </span>
              <span className="text-[11px] text-muted">{petWeightText(pet.weightKg)}</span>
            </div>
          </div>
        </div>
        {pet.vaccineSummary && (
          <p className="mt-2.5 flex items-center gap-1.5 border-t border-navy/5 pt-2.5 text-[11px] text-muted">
            <IconCalendar className="h-3.5 w-3.5 shrink-0 text-gold-dark" />
            <span className="truncate">{pet.vaccineSummary}</span>
          </p>
        )}
      </button>
    </li>
  )
}

function EmptyPets({ onAdd }: { onAdd: () => void }) {
  return (
    <div className="mt-6 rounded-3xl bg-white/85 p-8 text-center shadow-soft ring-1 ring-navy/5">
      <IconPaw className="mx-auto h-10 w-10 text-gold-dark/50" />
      <p className="mt-3 text-sm font-bold text-ink">还没有宠物档案</p>
      <p className="mx-auto mt-1 max-w-[16rem] text-xs leading-relaxed text-muted">
        先建立毛孩子的档案，之后预约服务、查看托管日报都会用到它。
      </p>
      <button
        type="button"
        onClick={onAdd}
        className="mt-4 inline-flex h-11 items-center gap-1.5 rounded-2xl bg-btn-gradient px-6 text-sm font-semibold text-white shadow-btn transition hover:opacity-95 active:scale-[0.99]"
      >
        <IconPlus className="h-4 w-4" /> 添加宠物
      </button>
    </div>
  )
}

function SkeletonPetCard() {
  return (
    <div className="animate-pulse rounded-3xl bg-white/70 p-4 ring-1 ring-navy/5" aria-hidden="true">
      <div className="flex gap-3">
        <div className="h-14 w-14 shrink-0 rounded-2xl bg-warmgray" />
        <div className="flex-1">
          <div className="h-4 w-1/3 rounded bg-warmgray" />
          <div className="mt-2 h-3 w-1/2 rounded bg-warmgray" />
          <div className="mt-3 h-4 w-24 rounded-full bg-warmgray" />
        </div>
      </div>
    </div>
  )
}
