import { http } from './client'

/**
 * 平台（ADMIN）侧接口层。闸都不是门店码——平台身份不该靠"某店归属"拿到权限。
 *
 * ADR-044 之前 ADMIN 被锁在总部虚拟店（ctx=1），而总部店没有业务数据，
 * 于是"平台管理员"在界面上表现为**一屏空态**、比店长还小；本片把矩阵:54
 * 「ADMIN 看所有门店」真正落地：可代入任意 OPEN 门店，读+做该店前台动作。
 */

/** `GET /api/v1/admin/stores` 的行（后端最小投影：只给下拉需要的三字段）。 */
export interface AdminStore {
  storeId: string
  name: string | null
  status: string
}

export const adminApi = {
  /**
   * 可代入的门店清单（闸 `store:manage`，V2 种子实测仅 ADMIN 持有 → 单店店长拿不到全网店名）。
   * 过滤口径与 `/public/stores` 同源：OPEN ∧ 非总部虚拟店 ∧ 未删，id 升序。
   *
   * <p>**必须显式传入门店锚点**（参数是必填的，就是为了不给"默认值"留口子）：这条请求发生在
   * `storeContext.load()` 期间，那时上下文还没建立，`currentStoreId()` 只能返回 null ⇒
   * 旧的无参写法在 fetch 之前就被 `NO_STORE_CTX` 拦下，再被调用方的空 catch 静默吞掉，
   * 结果是平台管理员的候选门店永远只剩总部虚拟店，界面上连门店切换器都不出现（ADR-044 的代店入口被自己锁死）。
   *
   * <p>调用方**只允许**传服务端 identities 返回的归属店（`storeContext` 里取 `pickDefault(stores)`），
   * 不允许取自 URL/sessionStorage/任何用户可编辑处；服务端仍会按该 ctx 复核 `store:manage`，
   * 传错店的后果是 10003，不是读到别的数据。
   */
  stores: (anchorStoreId: string) => {
    if (!anchorStoreId) {
      throw new Error('adminApi.stores 需要一个来自服务端 identities 的门店锚点')
    }
    return http.get<AdminStore[]>('/admin/stores', { auth: true, store: anchorStoreId })
  },
}
