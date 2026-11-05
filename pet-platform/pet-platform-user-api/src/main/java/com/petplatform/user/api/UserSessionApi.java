package com.petplatform.user.api;

/**
 * 会话撤销端口（ADR-055）：平台侧员工停用/离职要"踢下线"——清空该用户 refresh 白名单，
 * 存量 access 由 TTL（2h）自然过期；黑名单只对已知 jti 有意义，平台端点拿不到目标用户的当前 access jti。
 *
 * <p>接线条款出处：{@code AuthService.revokeAll} 的 javadoc「待接：ADMIN 封禁账号落地时必须同调本方法」。
 * 员工侧权限的即时清空不走本端口，由 app 的 PermissionAssembler.evictAll 完成（所有权限 SQL
 * 本来就要求 emp_employee.status='ACTIVE'，evict 后下次请求重建即空集）。
 */
public interface UserSessionApi {

    /** 作废该用户全部 refresh 凭据；C 端登录态在 access TTL 内不受影响（有意为之，见 ADR-055）。 */
    void revokeAllSessions(Long userId);
}
