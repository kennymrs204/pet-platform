package com.petplatform.appointment.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.petplatform.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 预约（apt_appointment，聚合根，表 Owner=appointment；ADR-016）。
 * 状态机写操作全部条件更新（AppointmentCommandMapper），version 随行递增。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("apt_appointment")
public class AppointmentEntity extends BaseEntity {

    private String appointmentNo;
    private Long userId;
    private Long petId;
    private Long storeId;
    private Long storeServiceId;
    private Long employeeId;
    private Long orderId;
    private LocalDate serviceDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private LocalDate boardingCheckinDate;
    private LocalDate boardingCheckoutDate;
    private String serviceNameSnapshot;
    /** 分 */
    private Long priceSnapshot;
    private Integer durationSnapshot;
    /** S7 取消政策快照：创建时有效值（免取消窗口小时；历史行 NULL=取消时 fallback 现政策）。 */
    private Integer cancelFreeHoursSnapshot;
    /** S7 取消政策快照：创建时有效值（扣费比例%，写入前已钳 0..100；历史行 NULL=fallback 现政策）。 */
    private Integer cancelPenaltyRateSnapshot;
    /** PENDING_PAYMENT/CONFIRMED/WAIT_SERVICE/SERVING/COMPLETED/CANCELLED */
    private String status;
    private String cancelledReason;
    private String cancelByRole;
    private String remark;
    private LocalDateTime expireAt;
    /** CUSTOMER/RECEPTIONIST */
    private String source;
    private LocalDateTime actualStartAt;
    private LocalDateTime actualEndAt;
    private Integer version;
}
