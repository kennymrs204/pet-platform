import { http } from './client'

/**
 * C 端账号资料（ADR-027，后端 CustomerProfileController）。权限 `account:profile:manage`（通用码恒授）。
 *
 * 三条必须守住的契约：
 *  1. **PUT 是全量提交**（ADR-010 §6 同族）：null/空白 = 清空该列，昵称归一写 ''。
 *     所以只想改昵称时，也必须把 avatar / defaultContactPhone / preferredStoreId 的当前值原样带回；
 *  2. `phone`、`status` **不可经本端点修改**（换绑手机是独立流程），请求体里根本不接受这两个字段；
 *  3. GET 会顺带 `ensureProfile`（存量无档案账号也能取到），本人可见完整手机号。
 *
 * 头像：`avatar` 存对象存储 key（ADR-029 presigned 直传，scene=`USER_AVATAR`），
 * `avatarUrl` 是后端签发的读取地址（600s）；两处都在「编辑资料」的 AvatarPicker 里写回。
 * 注意 PUT 全量提交——换头像也必须把其余字段原样带回，否则会被清空。
 */
export interface CustomerProfileView {
  userId: string
  nickname: string | null
  avatar: string | null
  avatarUrl: string | null
  phone: string | null
  status: string | null
  registeredAt: string | null
  defaultContactPhone: string | null
  preferredStoreId: string | null
  marketingOptIn: boolean | null
}

export interface ProfileUpdateBody {
  nickname: string | null
  avatar: string | null
  defaultContactPhone: string | null
  preferredStoreId: string | null
  marketingOptIn: boolean
}

export const profileApi = {
  get: () => http.get<CustomerProfileView>('/customer/profile', { auth: true }),
  update: (body: ProfileUpdateBody) => http.put<CustomerProfileView>('/customer/profile', body, { auth: true }),
}

/**
 * 组装 PUT 全量请求体（纯函数，便于断言"漏传即清空"这条坑）。
 * 偏好门店空串归一为 null；昵称 trim 后空串按后端约定传 null（落 '' 后前端回落展示）。
 */
export function buildProfileUpdate(
  current: Pick<CustomerProfileView, 'avatar' | 'preferredStoreId'>,
  sel: { nickname: string; defaultContactPhone: string; preferredStoreId: string; marketingOptIn: boolean },
): ProfileUpdateBody {
  return {
    nickname: sel.nickname.trim() || null,
    avatar: current.avatar ?? null,
    defaultContactPhone: sel.defaultContactPhone.trim() || null,
    preferredStoreId: sel.preferredStoreId.trim() || null,
    marketingOptIn: sel.marketingOptIn,
  }
}

/** 昵称展示回落：ADR-027 §3 规定昵称可空，前端统一回落「用户+手机号尾4位」，没有手机号就回落「铲屎官」。 */
export function displayName(p: CustomerProfileView | null): string {
  const nick = p?.nickname?.trim()
  if (nick) return nick
  const phone = p?.phone ?? ''
  if (phone.length >= 4) return `用户${phone.slice(-4)}`
  return '铲屎官'
}
