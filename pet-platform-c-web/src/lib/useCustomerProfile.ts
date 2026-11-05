import { useCallback, useEffect, useState } from 'react'
import { CustomerProfileView, profileApi } from '../api/profile'

/**
 * 账号资料的模块级缓存（主页问候语与「我的」页都要同一份，避免各拉一次）。
 * 任一页保存成功后调 `invalidateProfile()`，另一页下次挂载即取新值。
 */
let cached: CustomerProfileView | null = null
let pending: Promise<CustomerProfileView | null> | null = null

export function invalidateProfile(): void {
  cached = null
}

function load(): Promise<CustomerProfileView | null> {
  if (cached) return Promise.resolve(cached)
  if (!pending) {
    pending = profileApi
      .get()
      .then((p) => {
        cached = p
        return p
      })
      .catch(() => null)
      .finally(() => {
        pending = null
      })
  }
  return pending
}

export function useCustomerProfile() {
  const [profile, setProfile] = useState<CustomerProfileView | null>(cached)
  const [loading, setLoading] = useState(!cached)

  const reload = useCallback(() => {
    setLoading(true)
    load().then((p) => {
      setProfile(p)
      setLoading(false)
    })
  }, [])

  useEffect(() => {
    if (cached) {
      setProfile(cached)
      setLoading(false)
      return
    }
    reload()
  }, [reload])

  return { profile, loading, reload }
}
