package com.petplatform.appointment.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 本店客户聚合行（ADR-017 §1：distinct(apt_appointment.user_id WHERE store_id=本店 ∧ deleted=0)）。
 * 员工账号的剔除由调用方（app 装配层）经 EmployeeRosterApi 完成——预约域不认识员工域。
 * visitCount 计入全部未删预约（含 CANCELLED，与"客户集"口径同源，不另立标准）；
 * hasValidAppointment = 存在 status ≠ CANCELLED 的预约（仅取消过预约的客户不隐藏，靠此标记呈现）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerAggView {

    private Long userId;
    private LocalDate firstVisitDate;
    private LocalDate lastVisitDate;
    private long visitCount;
    private boolean hasValidAppointment;
}
