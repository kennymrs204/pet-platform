package com.petplatform.appointment.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 履约动作结果（ADR-048）：分配 / 开始服务 / 完成服务 三个命令共用一个视图。
 *
 * <p>{@code changed} 的口径与 {@link AppointmentCheckinView} 一致——
 * <b>true=本次调用真的推进了；false=幂等重放</b>（重复点、两个店长同时点、员工已自己点过）。
 * 幂等重放按成功回，不报红色"非法状态流转"：用户看到的是同一件事已经成了，
 * 把它做成错误反而会诱导重复提交。
 *
 * <p>为什么把 petId/storeServiceId/actualStartAt 也带出来：完成服务时 app 装配层要在
 * <b>同一个事务</b>里往 {@code ful_bath_record}／{@code ful_walk_record} 写履约记录，
 * 那几个字段是记录的必要列。让调用方回读一次预约（多一次查询、且读到的可能是被并发改过的另一版本）
 * 不如让状态落定的那一手直接把证据交出来。
 */
@Data
@Builder
public class AppointmentTaskView {

    private Long appointmentId;
    private String appointmentNo;
    /** 动作之后的状态：分配不改状态；开始=SERVING；完成=COMPLETED */
    private String status;
    /** true=本次调用推进了；false=幂等重放 */
    private boolean changed;

    private Long storeId;
    private Long petId;
    private Long storeServiceId;
    private Long employeeId;
    private LocalDate serviceDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private LocalDateTime actualStartAt;
    private LocalDateTime actualEndAt;
}
