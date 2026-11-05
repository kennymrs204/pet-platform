/**
 * 托管页写/读动作的前端等价判据。
 *
 * 权威闸仍在后端（权限码 + 门店上下文 + 状态机）；前端拿不到权限码集（identities 只回每店 roles），
 * 故按 V2/V3/V5 的角色→权限矩阵推导，用于**不给必然失败的按钮**：
 * - boarding:checkin：ADMIN / STORE_MANAGER / RECEPTIONIST / CARETAKER
 * - boarding:checkout：ADMIN / STORE_MANAGER / RECEPTIONIST
 * - boarding:care:create：ADMIN / STORE_MANAGER / CARETAKER（服务层再叠加“仅主责护理员”）
 * - boarding:capacity:view：ADMIN / STORE_MANAGER / RECEPTIONIST / CARETAKER
 * - customer:view:store：ADMIN / STORE_MANAGER / RECEPTIONIST
 * - 补建候选读 `GET /store/appointments` 还需 appointment:view:store（ADMIN / STORE_MANAGER / RECEPTIONIST），
 *   因此补建入口 = boarding:checkin ∧ appointment:view:store。
 *
 * `platformAdmin` 来自 storeContext（ADMIN 可代入任意 OPEN 门店，ADR-044/V5），等价于持有上述全部门店执行码。
 * 判据只决定“显示/禁用”，不放宽任何服务端校验；角色映射若与后端漂移，后端 10003 仍是最终裁决。
 */

export interface BoardingCapabilities {
  checkin: boolean
  checkout: boolean
  careCreate: boolean
  provision: boolean
  capacity: boolean
  customer: boolean
}

const CHECKIN_ROLES = ['ADMIN', 'STORE_MANAGER', 'RECEPTIONIST', 'CARETAKER'] as const
const CHECKOUT_ROLES = ['ADMIN', 'STORE_MANAGER', 'RECEPTIONIST'] as const
const CARE_CREATE_ROLES = ['ADMIN', 'STORE_MANAGER', 'CARETAKER'] as const
const CAPACITY_ROLES = ['ADMIN', 'STORE_MANAGER', 'RECEPTIONIST', 'CARETAKER'] as const
const CUSTOMER_ROLES = ['ADMIN', 'STORE_MANAGER', 'RECEPTIONIST'] as const
/** 补建候选复用 /store/appointments，需门店范围预约读权限 */
const APPOINTMENT_VIEW_STORE_ROLES = ['ADMIN', 'STORE_MANAGER', 'RECEPTIONIST'] as const

export const PLATFORM_ADMIN_CAPABILITIES: BoardingCapabilities = {
  checkin: true,
  checkout: true,
  careCreate: true,
  provision: true,
  capacity: true,
  customer: true,
}

function hasAnyRole(
  roles: readonly string[] | null | undefined,
  allowed: readonly string[],
): boolean {
  if (!roles || roles.length === 0) return false
  const set = new Set(roles)
  return allowed.some((role) => set.has(role))
}

export function boardingCapabilities(
  platformAdmin: boolean,
  roles: readonly string[] | null | undefined,
): BoardingCapabilities {
  if (platformAdmin) return { ...PLATFORM_ADMIN_CAPABILITIES }
  const checkin = hasAnyRole(roles, CHECKIN_ROLES)
  return {
    checkin,
    checkout: hasAnyRole(roles, CHECKOUT_ROLES),
    careCreate: hasAnyRole(roles, CARE_CREATE_ROLES),
    provision: checkin && hasAnyRole(roles, APPOINTMENT_VIEW_STORE_ROLES),
    capacity: hasAnyRole(roles, CAPACITY_ROLES),
    customer: hasAnyRole(roles, CUSTOMER_ROLES),
  }
}

/**
 * 护理记录写（ADR-015 §5 补记 2026-10-27）：持 care:create 的本店员工即可写——
 * 该单主责天然持码；未指派/主责不在场时店长等持码者可代录，记录 caretaker_id 落实际写入人。
 * eid=0（ADMIN 代店无归属）后端仍拒，前端同口径不给按钮。
 */
export function canCareForStay(
  caps: Pick<BoardingCapabilities, 'careCreate'>,
  myEmployeeId: string | null | undefined,
): boolean {
  return caps.careCreate && !!myEmployeeId
}
