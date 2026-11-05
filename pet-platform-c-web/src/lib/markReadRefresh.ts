/**
 * 单条已读后的摘要刷新边界：必须等 markRead 落定（成功或失败）后再刷新，
 * 否则刷新请求会和写入抢跑，把角标停回"还没读"的数字。
 *
 * 失败仍沿用消息中心既有语义：本地乐观 UI 已置已读，这里静默吞掉写失败，
 * 不产生未处理的 Promise rejection；下次加载/刷新以服务端为准回正。
 */
export async function refreshUnreadAfterMarkRead(
  markRead: Promise<unknown>,
  refresh: () => void,
): Promise<void> {
  try {
    await markRead
  } catch {
    // 写失败不在这里提示；保留原页面行为
  } finally {
    refresh()
  }
}
