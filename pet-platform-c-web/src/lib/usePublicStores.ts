import { useEffect, useState } from 'react'
import { DEMO_STORES, PublicStoreVO, storeApi } from '../api/store'

/**
 * 公开门店列表（ADR-022：GET /public/stores，返回纯数组不分页）。
 * 首页/预约页/订单页都要同一份数据（选门店、把 storeId 显示成门店名），
 * 故做**模块级缓存**：整个会话只发一次请求，后续页面复用。
 * 失败时回退 api/store 的 DEMO_STORES 并置 demo=true，由页面黄条明示；
 * 后端若再次不可用也不会让页面变成空白。
 */
let cached: { stores: PublicStoreVO[]; demo: boolean } | null = null
let pending: Promise<{ stores: PublicStoreVO[]; demo: boolean }> | null = null

function load(): Promise<{ stores: PublicStoreVO[]; demo: boolean }> {
  if (cached) return Promise.resolve(cached)
  if (!pending) {
    pending = storeApi
      .listPublicStores()
      .then((d) => {
        cached = { stores: Array.isArray(d) ? d : [], demo: false }
        return cached
      })
      .catch(() => {
        cached = { stores: DEMO_STORES, demo: true }
        return cached
      })
      .finally(() => {
        pending = null
      })
  }
  return pending
}

export function usePublicStores() {
  const [state, setState] = useState<{ stores: PublicStoreVO[]; demo: boolean; loading: boolean }>({
    stores: cached?.stores ?? [],
    demo: cached?.demo ?? false,
    loading: !cached,
  })

  useEffect(() => {
    let alive = true
    load().then((r) => {
      if (alive) setState({ stores: r.stores, demo: r.demo, loading: false })
    })
    return () => {
      alive = false
    }
  }, [])

  const nameOf = (id: string | number) => state.stores.find((s) => String(s.id) === String(id))?.name ?? `门店 ${id}`
  return { ...state, nameOf }
}
