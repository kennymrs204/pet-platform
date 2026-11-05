import { useEffect, useRef, useState } from 'react'
import Modal from '../ui/Modal'
import { BizError } from '../../api/types'
import { petsApi, type PetItem } from '../../api/pets'
import { petDeleteErrorText } from '../../lib/petManage'
import { cx } from '../../lib/cx'

/**
 * B 端宠物删除确认。后端负责未完成预约/托管阻断、门店归属和历史保留；
 * 本组件只负责确认、防重复、失败保留与关闭/切换时的请求中止。
 */
export default function PetDeleteModal({
  pet,
  onClose,
  onDeleted,
}: {
  pet: PetItem
  onClose: () => void
  onDeleted: () => void
}) {
  const [deleting, setDeleting] = useState(false)
  const [error, setError] = useState('')
  const deletingRef = useRef(false)
  const acRef = useRef<AbortController | null>(null)
  const aliveRef = useRef(true)

  useEffect(() => {
    aliveRef.current = true
    return () => {
      aliveRef.current = false
      acRef.current?.abort()
    }
  }, [])

  const requestClose = () => {
    if (deletingRef.current) return
    onClose()
  }

  const confirm = async () => {
    if (deletingRef.current) return
    deletingRef.current = true
    setDeleting(true)
    setError('')
    const ac = new AbortController()
    acRef.current = ac
    try {
      await petsApi.remove(pet.id, { signal: ac.signal })
      if (!aliveRef.current || ac.signal.aborted) return
      onDeleted()
    } catch (err) {
      if (!aliveRef.current || ac.signal.aborted) return
      const biz = err instanceof BizError ? err : null
      setError(petDeleteErrorText(biz?.code ?? -1, biz?.message ?? ''))
    } finally {
      if (acRef.current === ac) acRef.current = null
      deletingRef.current = false
      if (aliveRef.current) setDeleting(false)
    }
  }

  return (
    <Modal
      open
      title={`删除宠物档案 · ${pet.name}`}
      description="删除后该宠物不能再关联新预约、新托管或新服务；历史业务数据不会被级联删除。"
      onClose={requestClose}
      footer={
        <>
          <button
            type="button"
            disabled={deleting}
            onClick={requestClose}
            className="h-9 rounded-lg bg-white px-4 text-xs font-semibold text-ink ring-1 ring-line transition hover:border-blue hover:text-blue disabled:opacity-60"
          >
            取消
          </button>
          <button
            type="button"
            disabled={deleting}
            onClick={() => void confirm()}
            className="flex h-9 items-center gap-2 rounded-lg bg-[#E8506B] px-4 text-xs font-bold text-white transition hover:bg-[#D9415D] disabled:cursor-not-allowed disabled:opacity-50"
          >
            {deleting ? '删除中…' : '确认删除'}
          </button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        <div className="rounded-xl bg-rose-50 px-3.5 py-3 text-[11px] leading-relaxed text-[#B4233A] ring-1 ring-rose-100">
          <p className="font-bold">确认删除「{pet.name}」的宠物档案？</p>
          <p className="mt-1">
            如存在进行中或未完成的预约、托管，后端会拒绝删除并说明原因。
            已完成的预约、履约、托管和健康档案变更历史会保留。
          </p>
        </div>
        {error && (
          <p className="rounded-lg bg-rose-50 px-3 py-2 text-[11px] leading-relaxed text-[#E8506B]" role="alert">
            {error}
          </p>
        )}
        {deleting && (
          <p className="flex items-center gap-1.5 text-[11px] text-muted" role="status">
            <Spinner /> 正在删除，请勿重复操作…
          </p>
        )}
      </div>
    </Modal>
  )
}

function Spinner() {
  return (
    <span
      className={cx('inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-blue border-t-transparent')}
      aria-hidden="true"
    />
  )
}
