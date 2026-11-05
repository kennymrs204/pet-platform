package com.petplatform.appointment.api.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 可约查询响应（第八阶段 §4.2 GET /customer/store-services/{id}/availability，ADR-016 §7）。
 * INSTANT 返回 slots（仅可用时段）；MULTI_DAY 返回 kennels（区间空闲笼位）；
 * allowUserPickEmployee=true 时附 employeeOptions。与创建共用同一算法体。
 */
@Data
@Builder
public class AvailabilityView {

    private Long storeServiceId;
    /** INSTANT/MULTI_DAY */
    private String fulfillmentType;
    private String date;
    private Boolean allowUserPickEmployee;
    private List<Slot> slots;
    private List<KennelOption> kennels;
    private List<EmployeeOption> employeeOptions;

    /** 可约时段（30min 对齐）。 */
    @Data
    @Builder
    public static class Slot {
        private String start;
        private String end;
    }

    /** 空闲笼位。 */
    @Data
    @Builder
    public static class KennelOption {
        private Long id;
        private String name;
        private String type;
    }

    /** 可选员工。 */
    @Data
    @Builder
    public static class EmployeeOption {
        private Long id;
        private String name;
    }
}
