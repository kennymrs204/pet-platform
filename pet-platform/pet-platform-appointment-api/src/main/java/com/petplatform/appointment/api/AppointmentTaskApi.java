package com.petplatform.appointment.api;

import com.petplatform.appointment.api.dto.AppointmentTaskView;

/**
 * 履约命令（ADR-048）：把"已支付并确认"的预约推进到"有人负责 → 服务中 → 已完成"。
 *
 * <p>与 {@link AppointmentCommandApi} 的分工：那个接口是<b>支付/取消链</b>的状态机
 * （由事件桥与退款链调用），本接口是<b>门店履约链</b>（由 B 端 HTTP 命令调用）。
 * 两者写同一张表但走互不相同的条件更新前置条件，因此互不覆盖：
 * 履约链只在 {@code CONFIRMED|WAIT_SERVICE|SERVING} 之间移动，取消链只从 {@code CONFIRMED} 走向
 * {@code CANCEL_REQUESTED/CANCELLED}，一旦 SERVING 就都不再可取消（50004 既有语义）。
 *
 * <p>调用方=app 装配层的门店侧控制器：它负责"谁有权操作这一单"（权限码 + 门店上下文 +
 * 员工只能动派给自己的单），本接口只负责业务合法性（状态窗、员工班次/技能/档期、跨店）。
 * 之所以要把 {@code storeWide}/{@code allowReassign} 这类<b>判定结果</b>传进来而不是自己读权限，
 * 与 {@code RefundApprovalApi} 同因：只有装配层能同时看到权限码集合与门店上下文，
 * 让领域服务去猜权限等于把 RBAC 复制第二份。
 */
public interface AppointmentTaskApi {

    /**
     * 分配/改派服务人员：写 {@code employee_id}，<b>不改状态</b>。
     *
     * <p>前置：预约属于 {@code storeCtx} 这家店（跨店与不存在同口径 10004）、非托管单
     * （托管的执行人由入住时分的笼位+护理员决定，见 ADR-042 同族禁令）、状态在
     * {@code CONFIRMED|WAIT_SERVICE}（已取消/审核中 50003）；另有一个<b>窄口径</b>（2026-10-27
     * 履约轮）：SERVING 且当前<b>无执行人</b>时允许首派——"开始不要求已指派"曾让这类单完成不了
     * （{@code ful_*_record.employee_id} NOT NULL）又改派不得，两头堵死；履约记录还没写，
     * 补执行人不存在"谁做的"失据。已有执行人的 SERVING 单照旧 50004（那才是改派）。
     * 目标员工在本店在职、当日有班、技能覆盖该服务、且该时段不与他的其它预约冲突
     * （复用创建链同一套判据 {@code requireEmployeeBookable}，不另写一份）。
     *
     * <p>幂等：同一员工重复分配回 {@code changed=false}；两个店长同时改派由 {@code version} CAS
     * 保证只有一人写进去。
     *
     * @param storeCtx       当前门店上下文（X-Store-Id），用于门店隔离
     * @param allowReassign  调用方是否持有 {@code appointment:reassign}；
     *                       该单已有<b>不同</b>执行人时为 false ⇒ 10003（首派只需 assign 码）
     */
    AppointmentTaskView assignEmployee(Long appointmentId, Long employeeId, Long storeCtx, boolean allowReassign);

    /**
     * 开始服务：{@code CONFIRMED|WAIT_SERVICE → SERVING} 并写 {@code actual_start_at}。
     * 已是 SERVING/COMPLETED 回 {@code changed=false}（幂等）；未支付/已取消/审核中 50003。
     *
     * <p><b>必须已指派</b>（2026-10-27 履约轮，ADR-054 修订）：未指派单开始会让履约记录
     * 无处挂执行人（NOT NULL），完成后又不能补派——原死锁链的源头，故在本闸拦截 50003，
     * 门店先分配再开始。
     *
     * @param operatorEmployeeId 操作人员工 id（无员工身份传 null）
     * @param storeWide          是否持有 {@code appointment:view:store}（店长/前台/ADMIN）；
     *                           false 时只能开始<b>派给自己</b>的单（§七.C，越权 10003）
     */
    AppointmentTaskView startService(Long appointmentId, Long storeCtx, Long operatorEmployeeId, boolean storeWide);

    /**
     * 完成服务：{@code SERVING → COMPLETED} 并写 {@code actual_end_at}。
     * 已是 COMPLETED 回 {@code changed=false}；<b>未开始就完成 50003</b>——
     * 宁可让门店先点一次开始，也不能凭空造出"服务时长为零"的完成事实。
     *
     * <p>履约记录（{@code ful_bath_record}／{@code ful_walk_record}）不在本接口内写：
     * 那是 fulfillment 表 Owner 的地盘，由 app 装配层在<b>同一事务</b>里跟着调用
     * {@code ServiceRecordCommandApi}，两边同生同死。
     */
    AppointmentTaskView finishService(Long appointmentId, Long storeCtx, Long operatorEmployeeId, boolean storeWide);

    /**
     * 换台位（ADR-048 §8 挂账核销）：把未开始的洗护单从当前台位挪到目标台位。
     *
     * <p><b>零预约列写</b>：台位真相只在占用行（{@code sch_resource_schedule}，ADR-038），
     * 预约表上没有 resource_id 列，因此没有 version 可 CAS——合法性由<b>事务内重验状态窗</b>保证：
     * 占用行的增删与状态读在同一事务里，读到什么就是什么，没有"读过期状态再写列"的竞态窗。
     *
     * <p>前置：非托管单（笼位归入住链管，ADR-042 同族禁令）、状态在 {@code CONFIRMED|WAIT_SERVICE}
     * （SERVING/COMPLETED → 50004，服务已开始换台位会让履约事实与台位对不上）、
     * 目标台位属于本店且 {@code AVAILABLE}、类型与服务要求的资源类型一致、
     * 该时段无其他预约占用（冲突 → 40004 <b>报错让门店重挑</b>，不自动换第二选择——
     * 门店眼里"空台位"是有 business 含义的选择，机器代选会悄悄违背门店意图）。
     *
     * <p>幂等：目标台位==当前占用台位回 {@code changed=false}，不释放重占、不撞自己。
     * 顺序遵循全仓锁序"资源行 → 占用行"（与改期链一致）。
     *
     * @param storeCtx 当前门店上下文（X-Store-Id），用于门店隔离；跨店目标台位按不存在处理
     */
    AppointmentTaskView changeResource(Long appointmentId, Long targetResourceId, Long storeCtx);

    /**
     * 只读任务视图：与 {@link #startService}/{@link #finishService} 同一套门店范围与
     * "执行人本人或店长/前台"判据，但<b>不推进任何状态</b>。
     *
     * <p>给"已完成的履约记录补照片"这类附加操作做授权前置：调用方（app 装配层）拿它确认
     * 预约属于当前门店、当前操作人可操作、服务类型由目录判定；判据不复制第二份。
     */
    AppointmentTaskView taskOf(Long appointmentId, Long storeCtx, Long operatorEmployeeId, boolean storeWide);
}
