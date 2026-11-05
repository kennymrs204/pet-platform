/**
 * 排班管理权限的前端等价判据（员工详情「排班」Tab 与相关入口的可见性）。
 *
 * 权威闸在后端：`StoreScheduleController` 以 `principal.permissions().contains("schedule:manage")` 判定。
 * 前端拿不到权限码集（identities 只回每店 roles），故按 V2 角色→权限映射推导：**仅 ADMIN 与 STORE_MANAGER
 * 持有 `schedule:manage`**。两者都来自服务端可信上下文——
 * - `platformAdmin`：storeContext 由 `identities.stores[].roles` 含 ADMIN 推出（持有全局 ADMIN 角色，
 *   经 PermissionAssembler 对任意 OPEN 代入店并入 ADMIN 码集，故必含 schedule:manage）；
 * - `currentStoreRoles`：当前门店归属的角色码（STORE_MANAGER 在该店即持 schedule:manage）。
 *
 * 这是「现有可信权限上下文」的等价机制，不是按某个 UI 角色徽章臆断；后端仍是最终裁决，
 * 且已加固为「非管理者显式查他人 employeeId → 10003」，即便前端判据有偏差也不会把本人排班冒充成他人。
 */

/** 员工详情抽屉的 Tab 键（与 EmployeesPage 的 EMP_TABS 对齐）。 */
export type EmpDetailTabKey = 'overview' | 'stores' | 'skills' | 'shifts'

const ALL_EMP_DETAIL_TABS: EmpDetailTabKey[] = ['overview', 'stores', 'skills', 'shifts']

/** 当前操作者是否具备排班管理能力（schedule:manage 的前端等价判据）。 */
export function canManageSchedule(platformAdmin: boolean, currentStoreRoles: string[]): boolean {
  return platformAdmin || currentStoreRoles.includes('STORE_MANAGER')
}

/**
 * 员工详情可见 Tab：无排班管理权者隐藏「排班」Tab。
 * 隐藏即不挂载 ShiftsTab，从而**不会触发** `weeklyShifts(targetEmployeeId)` 去查他人排班
 * （后端对只持 self:view 者会强制返回本人排班，那会被错显示成目标员工的排班）。
 */
export function visibleEmpDetailTabs(canManage: boolean): EmpDetailTabKey[] {
  return canManage ? ALL_EMP_DETAIL_TABS : ALL_EMP_DETAIL_TABS.filter((t) => t !== 'shifts')
}
