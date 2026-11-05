package com.petplatform.boarding.api.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 入住办理（ADR-015 §3）。实际入住时间只由服务端写入，不在本请求；
 * kennelResourceId 空 = 沿用预约预占笼位；非空 = 迁移到指定笼位（服务端校验类型/状态/冲突）。
 */
@Data
public class CheckinRequest {

    private Long kennelResourceId;

    private Long caretakerId;

    @Size(max = 500, message = "特殊说明不超过500字")
    private String specialInstructions;
}
