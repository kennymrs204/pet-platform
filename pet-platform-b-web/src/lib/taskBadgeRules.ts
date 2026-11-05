/** 任务角标只接受非负整数；缺失/NaN/负数一律视为未知，不能伪装成 0。 */
export function parseTodoTotal(value: string | number | null | undefined): number | null {
  if (value === null || value === undefined || value === '') return null
  const n = Number(value)
  if (!Number.isFinite(n) || n < 0) return null
  return Math.floor(n)
}

/** 0/未知隐藏；1-99 显示数字；100+ 显示 99+。 */
export function taskBadgeText(value: number | null): string | null {
  if (value === null || value <= 0) return null
  return value > 99 ? '99+' : String(value)
}
