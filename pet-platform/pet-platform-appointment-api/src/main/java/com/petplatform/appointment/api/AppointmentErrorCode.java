package com.petplatform.appointment.api;

import com.petplatform.common.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 预约域错误码（第八阶段 §2-2 预约段 50000）。本段当前只落地状态机相关码，
 * 预约创建段（50001/50002/50005/50006）随后续实现补入，禁止提前占用他段号。
 */
@Getter
@RequiredArgsConstructor
public enum AppointmentErrorCode implements ErrorCode {

    ILLEGAL_STATE_TRANSITION(50003, "非法状态流转"),
    /** 已开始履约不可取消（第八阶段 §2-2 50004；B 端 status 端点同源复用） */
    SERVICE_IN_PROGRESS(50004, "服务已开始，不可取消"),
    /** C3（§2-2 门店/目录段 30001，由预约校验链抛出，先例 72001 定义于使用方） */
    STORE_CLOSED(30001, "门店未营业"),
    /** C4（§2-2 31001） */
    SERVICE_OFF_SHELF(31001, "服务已下架"),
    /** 宠物状态不可预约（§2-2 预留值，ADR-016 落地：非 NORMAL/缺尺寸） */
    PET_NOT_BOOKABLE(20102, "宠物状态不可预约"),
    /** C8 防恶意占位（sys_config order.unpaid_limit_per_user） */
    TOO_MANY_UNPAID(50005, "存在过多未支付订单，请先完成支付"),
    /** §5.4 单用户单店单日创建上限（sys_config order.daily_create_limit_per_user_store） */
    DAILY_CREATE_LIMIT(50006, "今日预约创建已达上限"),
    /** C7 条2/3 */
    EMPLOYEE_NOT_WORKING(40001, "员工当日无排班"),
    EMPLOYEE_TIME_CONFLICT(40002, "员工时段冲突"),
    /** C7 条4 */
    EMPLOYEE_SKILL_MISMATCH(40003, "员工技能不匹配"),
    /** C5 */
    OUT_OF_BOOKING_WINDOW(50002, "超出可预约窗口");
    private final int code;
    private final String message;
}
