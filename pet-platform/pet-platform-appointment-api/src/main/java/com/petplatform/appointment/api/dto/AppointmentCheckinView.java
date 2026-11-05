package com.petplatform.appointment.api.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 到店签到结果（ADR-042）。
 *
 * <p>{@code changed=false} 表示"这单此前已在待服务及之后"——重复点击、两个前台同时点、
 * 或已经开始服务后再回来点签到，都按幂等成功回，而不是回一个红色的"非法状态流转"。
 * 前端据此把 toast 文案分成「已签到」与「此前已签到」。
 */
@Data
@Builder
public class AppointmentCheckinView {

    private Long appointmentId;
    private String appointmentNo;
    /** 签到后的状态：恒 WAIT_SERVICE */
    private String status;
    /** true=本次调用推进了状态；false=幂等重放（本来就已签到） */
    private boolean changed;
}
