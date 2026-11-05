package com.petplatform.app.service;

import com.petplatform.appointment.api.AppointmentQueryApi;
import com.petplatform.appointment.api.dto.PetServiceVisitView;
import com.petplatform.boarding.api.BoardingQueryApi;
import com.petplatform.boarding.api.dto.BoardingStayView;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.result.PageResult;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.employee.api.EmployeeQueryApi;
import com.petplatform.employee.api.dto.EmployeeBrief;
import com.petplatform.fulfillment.api.ServiceRecordQueryApi;
import com.petplatform.fulfillment.api.dto.ServiceRecordBrief;
import com.petplatform.fulfillment.api.dto.WalkRecordBrief;
import com.petplatform.pet.service.StorePetQueryService;
import lombok.Builder;
import lombok.Data;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * B 端宠物当前门店服务历史（ADR-014 后续增量）。
 *
 * <p>预约是列表主脊柱：{@code apt_appointment} 决定了该门店、该宠物、该执行人范围内的
 * 全部服务与真实状态；没有履约记录的预约也会保留。履约/托管表只做当前页的批量补充，
 * 不参与主列表筛选，也不产生重复行。
 *
 * <p>范围判定只认 AuthPrincipal：持 {@code appointment:view:store} 看全店；
 * 其余角色把当前员工 id 下推进预约 SQL（不接受客户端传 employeeId）。宠物可见性先复用
 * {@link StorePetQueryService#requireVisible(Long, AuthPrincipal, Long)}，不可见/不存在统一 20101。
 */
@Service
public class StorePetRecordQueryService {

    static final String CODE_STORE_WIDE = "appointment:view:store";
    static final int MAX_PAGE_SIZE = 100;

    private final StorePetQueryService storePetQueryService;
    private final AppointmentQueryApi appointmentQueryApi;
    private final ServiceRecordQueryApi serviceRecordQueryApi;
    private final BoardingQueryApi boardingQueryApi;
    private final EmployeeQueryApi employeeQueryApi;

    public StorePetRecordQueryService(StorePetQueryService storePetQueryService,
                                      AppointmentQueryApi appointmentQueryApi,
                                      ServiceRecordQueryApi serviceRecordQueryApi,
                                      BoardingQueryApi boardingQueryApi,
                                      EmployeeQueryApi employeeQueryApi) {
        this.storePetQueryService = storePetQueryService;
        this.appointmentQueryApi = appointmentQueryApi;
        this.serviceRecordQueryApi = serviceRecordQueryApi;
        this.boardingQueryApi = boardingQueryApi;
        this.employeeQueryApi = employeeQueryApi;
    }

    public PageResult<PetServiceRecordRow> page(Long storeId, AuthPrincipal principal, Long petId,
                                                int pageNum, int pageSize) {
        if (pageNum < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "分页参数非法");
        }
        storePetQueryService.requireVisible(storeId, principal, petId);

        boolean storeWide = principal.permissions().contains(CODE_STORE_WIDE);
        Long employeeScope = storeWide ? null : principal.employeeId();
        if (!storeWide && (employeeScope == null || employeeScope == 0L)) {
            return PageResult.of(0, pageNum, pageSize, List.of());
        }

        PageResult<PetServiceVisitView> visits =
                appointmentQueryApi.petServiceHistory(storeId, petId, employeeScope, pageNum, pageSize);
        if (visits.getList().isEmpty()) {
            return PageResult.of(visits.getTotal(), visits.getPageNum(), visits.getPageSize(), List.of());
        }

        List<Long> appointmentIds = visits.getList().stream()
                .map(PetServiceVisitView::getAppointmentId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, ServiceRecordBrief> bathRecords = serviceRecordQueryApi
                .bathRecordsByAppointments(appointmentIds);
        Map<Long, WalkRecordBrief> walkRecords = serviceRecordQueryApi
                .walkRecordsByAppointments(appointmentIds);
        Map<Long, BoardingStayView> boardingStays = boardingQueryApi
                .staysByAppointments(storeId, appointmentIds).stream()
                .collect(Collectors.toMap(BoardingStayView::getAppointmentId, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));

        Set<Long> employeeIds = new LinkedHashSet<>();
        for (PetServiceVisitView visit : visits.getList()) {
            if (visit.getEmployeeId() != null) employeeIds.add(visit.getEmployeeId());
        }
        for (BoardingStayView stay : boardingStays.values()) {
            if (stay.getCaretakerId() != null) employeeIds.add(stay.getCaretakerId());
        }
        Map<Long, String> employeeNames = employeeIds.isEmpty() ? Map.of()
                : employeeQueryApi.briefsInStore(employeeIds, storeId).stream()
                .collect(Collectors.toMap(EmployeeBrief::getEmployeeId, EmployeeBrief::getName,
                        (a, b) -> a, LinkedHashMap::new));

        List<PetServiceRecordRow> rows = new ArrayList<>(visits.getList().size());
        for (PetServiceVisitView visit : visits.getList()) {
            ServiceRecordBrief bath = bathRecords.get(visit.getAppointmentId());
            WalkRecordBrief walk = walkRecords.get(visit.getAppointmentId());
            BoardingStayView boarding = boardingStays.get(visit.getAppointmentId());
            String employeeName = visit.getEmployeeId() == null ? null : employeeNames.get(visit.getEmployeeId());
            String boardingCaretakerName = boarding == null || boarding.getCaretakerId() == null
                    ? null : employeeNames.get(boarding.getCaretakerId());
            rows.add(PetServiceRecordRow.builder()
                    .appointmentId(visit.getAppointmentId())
                    .appointmentNo(visit.getAppointmentNo())
                    .petId(visit.getPetId())
                    .serviceDate(visit.getServiceDate())
                    .startTime(visit.getStartTime())
                    .endTime(visit.getEndTime())
                    .serviceName(visit.getServiceName())
                    .status(visit.getStatus())
                    .priceSnapshot(visit.getPriceSnapshot())
                    .employeeId(visit.getEmployeeId())
                    .employeeName(employeeName)
                    .recordType(recordTypeOf(bath, walk, boarding))
                    .finishedAt(finishedAtOf(bath, walk, visit.getActualEndAt()))
                    .bathRecord(bath)
                    .walkRecord(walk)
                    .boardingStay(boarding)
                    .boardingCaretakerName(boardingCaretakerName)
                    .build());
        }
        return PageResult.of(visits.getTotal(), visits.getPageNum(), visits.getPageSize(), rows);
    }

    /** 只按真实履约/托管记录判类型；没有记录就是 OTHER，绝不用 serviceName 猜。 */
    static String recordTypeOf(ServiceRecordBrief bath, WalkRecordBrief walk, BoardingStayView boarding) {
        if (bath != null && walk == null) return "BATH";
        if (walk != null && bath == null) return "WALK";
        if (boarding != null && bath == null && walk == null) return "BOARDING";
        return "OTHER";
    }

    static LocalDateTime finishedAtOf(ServiceRecordBrief bath, WalkRecordBrief walk, LocalDateTime actualEndAt) {
        if (walk != null && walk.getFinishedAt() != null) return walk.getFinishedAt();
        if (bath != null && bath.getFinishedAt() != null) return bath.getFinishedAt();
        return actualEndAt;
    }

    @Data
    @Builder
    public static class PetServiceRecordRow {
        private Long appointmentId;
        private String appointmentNo;
        private Long petId;
        private LocalDate serviceDate;
        private LocalTime startTime;
        private LocalTime endTime;
        private String serviceName;
        /** 真实预约状态；前端负责文案/颜色映射。 */
        private String status;
        private Long priceSnapshot;
        private Long employeeId;
        private String employeeName;
        /** BATH/WALK/BOARDING/OTHER；只有真实详情记录才给出前三类。 */
        private String recordType;
        private LocalDateTime finishedAt;
        private ServiceRecordBrief bathRecord;
        private WalkRecordBrief walkRecord;
        private BoardingStayView boardingStay;
        private String boardingCaretakerName;
    }
}
