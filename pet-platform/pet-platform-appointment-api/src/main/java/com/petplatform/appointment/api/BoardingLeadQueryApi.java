package com.petplatform.appointment.api;

import com.petplatform.appointment.api.dto.BoardingLeadView;

import java.time.LocalDate;
import java.util.List;

/**
 * 托管「已下单待支付」只读查询（表 Owner：appointment；ADR-030）。
 *
 * <p>为什么是独立接口而不是往 {@code AppointmentQueryApi} 加方法：该接口与其实现
 * {@code AppointmentQueryService}/{@code AppointmentQueryMapper} 正被并行会话高频改动（取消/改期链路），
 * 新增文件零覆盖风险——与 ADR-018 的 {@code EmployeeRosterApi}、ADR-024 的 {@code StoreEmployeeQueryApi} 同法。
 */
public interface BoardingLeadQueryApi {

    /**
     * 本店**未支付**的跨天托管预约（ADR-016 口径：这类预约不会生成住单，本方法是它在 B 端的唯一可见出口）。
     *
     * <p>托管判定 = {@code boarding_checkin_date IS NOT NULL}——MULTI_DAY 的唯一持久特征，
     * 不依赖服务名快照、也不跨模块回查 catalog。
     * 入住日落在 {@code [from, to]} 闭区间；确定性排序 checkin_date ASC → id ASC；limit 由调用方钳制。
     */
    List<BoardingLeadView> pendingBoardingAppointments(Long storeId, LocalDate from, LocalDate to, int limit);
}
