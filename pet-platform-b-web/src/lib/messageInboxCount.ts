/**
 * 消息中心未读显示与「全部已读」按钮可见性的纯判据（C/B 各持一份，与 unreadCount 同风格）。
 *
 * <p>核心契约：这两个判断必须建立在<b>服务端完整收件箱未读数</b>上（跨分页），而不是当前已加载 `items`
 * 里 `!read` 的行数。否则第一页全读、后面还有未读时，按钮会被假性隐藏、头部会显示偏小的数。
 *
 * <p>`serverCount` 传"服务端全量未读"或 null：
 * - C：个人收件箱未读数（store_id=0，含所有类型，与 markAllRead 作用域一致）；
 * - B：门店消息中心完整未读行数（含 CHAT_MESSAGE，与 selectForUserInStore / markAllReadInStore 一致）——
 *   不是排除了 CHAT_MESSAGE 的铃铛 notificationCount。
 * null 表示"还不知道"（unread-summary 首帧未回或请求失败），绝不能当成真实 0。
 */

/** 头部展示的未读数：直接采用服务端全量计数；null=未知，由调用方渲染 '—'（不伪造成 0）。 */
export function inboxUnreadDisplay(serverCount: number | null): number | null {
  return serverCount
}

/**
 * 「全部已读」按钮是否可见/可执行：
 * - 服务端计数已知（含 0）→ 仅当 > 0 才显示；等于 0 说明整个收件箱确无未读，隐藏有效按钮；
 * - 服务端计数未知（null）→ 保守回退"当前已加载项是否有未读"，不因一个虚构的 0 谎称"已全部读完"。
 */
export function showMarkAllRead(serverCount: number | null, loadedUnread: number): boolean {
  return serverCount === null ? loadedUnread > 0 : serverCount > 0
}
