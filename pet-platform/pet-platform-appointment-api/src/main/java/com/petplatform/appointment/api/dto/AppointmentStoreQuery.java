package com.petplatform.appointment.api.dto;

import com.petplatform.common.time.BusinessDates;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/** 门店预约列表查询（storeId 必传且已由调用方完成归属校验；分页钳制在实现层）。 */
@Data
@Builder
public class AppointmentStoreQuery {

    private Long storeId;
    private LocalDate date;
    /** apt_appointment.status 枚举集合；空=不过滤 */
    private List<String> statuses;
    private Long employeeId;
    private int pageNum;
    private int pageSize;

    public static final int MAX_PAGE_SIZE = 100;

    public LocalDate effectiveDate() {
        return date != null ? date : BusinessDates.today();
    }
}
