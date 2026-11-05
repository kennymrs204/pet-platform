package com.petplatform.boarding.web;

import com.petplatform.boarding.api.dto.BoardingStayView;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.appointment.api.AppointmentQueryApi;
import com.petplatform.employee.api.EmployeeQueryApi;
import com.petplatform.employee.api.dto.EmployeeBrief;
import com.petplatform.pet.api.PetQueryApi;
import com.petplatform.pet.api.dto.PetSummary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 住单行 → 看板条目（宠物/护理员/客户引用回显）的共用装配（ADR-032 片2）。
 *
 * <p>抽出来的理由与 ADR-030 §4 把客户列抽成 {@link BoardingCustomerAssembler} 完全相同：
 * 在住列表与历史分页列表要出**同一形状的行**，两处各写一遍 pet/caretaker/customer 的取名与门控，
 * 早晚漂移成"历史页签没有客户列"或"两处脱敏口径不一致"。
 *
 * <p>取名一律走 api 批量接口（每行 0 次额外查询：一次 {@code summaries} + 一次 {@code avatarUrls}
 * + 一次 {@code briefsInStore} + 一次客户装配），不在 SQL 里 join 他模块表。
 */
@Component
public class BoardingBoardAssembler {

    private final PetQueryApi petQueryApi;
    private final EmployeeQueryApi employeeQueryApi;
    private final BoardingCustomerAssembler customerAssembler;
    private final AppointmentQueryApi appointmentQueryApi;

    public BoardingBoardAssembler(PetQueryApi petQueryApi, EmployeeQueryApi employeeQueryApi,
                                  BoardingCustomerAssembler customerAssembler,
                                  AppointmentQueryApi appointmentQueryApi) {
        this.petQueryApi = petQueryApi;
        this.employeeQueryApi = employeeQueryApi;
        this.customerAssembler = customerAssembler;
        this.appointmentQueryApi = appointmentQueryApi;
    }

    public List<StoreBoardingController.BoardingBoardItem> toItems(Long storeId, List<BoardingStayView> stays,
                                                                   AuthPrincipal principal) {
        if (stays.isEmpty()) {
            return List.of();
        }
        List<Long> petIds = stays.stream().map(BoardingStayView::getPetId).filter(Objects::nonNull).distinct().toList();
        List<Long> caretakerIds = stays.stream().map(BoardingStayView::getCaretakerId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, PetSummary> pets = petQueryApi.summaries(petIds).stream()
                .collect(Collectors.toMap(PetSummary::getId, Function.identity(), (a, b) -> a));
        // 历史住单引用的宠物可能已被逻辑删除（删除不级联历史业务）：档案属性一并不可见，
        // 只回填名字让历史行可读，而不是显示"宠物信息缺失"。
        List<Long> missingPetIds = petIds.stream().filter(id -> !pets.containsKey(id)).toList();
        Map<Long, String> deletedNames = missingPetIds.isEmpty() ? Map.of()
                : petQueryApi.namesIncludingDeleted(missingPetIds);
        Map<Long, String> avatarUrls = petQueryApi.avatarUrls(petIds);
        Map<Long, EmployeeBrief> caretakers = caretakerIds.isEmpty() ? Map.of()
                : employeeQueryApi.briefsInStore(caretakerIds, storeId).stream()
                .collect(Collectors.toMap(EmployeeBrief::getEmployeeId, Function.identity(), (a, b) -> a));
        Map<Long, BoardingCustomerAssembler.CustomerRef> customers = customerAssembler.byPet(petIds, principal);

        // 预约状态批量回填（与取名同纪律：一次批量查询，不在 SQL 里 join 他模块表）
        List<Long> appointmentIds = stays.stream().map(BoardingStayView::getAppointmentId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, String> aptStatuses = appointmentQueryApi.boardingStatuses(appointmentIds);

        return stays.stream().map(s -> {
            PetSummary pet = s.getPetId() == null ? null : pets.get(s.getPetId());
            String deletedName = pet == null && s.getPetId() != null ? deletedNames.get(s.getPetId()) : null;
            EmployeeBrief caretaker = s.getCaretakerId() == null ? null : caretakers.get(s.getCaretakerId());
            return StoreBoardingController.BoardingBoardItem.builder()
                    .id(s.getId())
                    .appointmentId(s.getAppointmentId())
                    .status(s.getStatus())
                    .appointmentStatus(s.getAppointmentId() == null ? null : aptStatuses.get(s.getAppointmentId()))
                    .checkinDate(s.getCheckinDate())
                    .checkoutDate(s.getCheckoutDate())
                    .actualCheckinAt(s.getActualCheckinAt())
                    .pet(pet == null ? (deletedName == null ? null
                            : StoreBoardingController.PetRef.builder().id(s.getPetId()).name(deletedName).build())
                        : StoreBoardingController.PetRef.builder()
                            .id(pet.getId()).name(pet.getName())
                            .species(pet.getSpecies()).breed(pet.getBreed()).gender(pet.getGender())
                            .avatar(pet.getAvatar()).avatarUrl(avatarUrls.get(pet.getId())).build())
                    .caretaker(caretaker == null ? null : StoreBoardingController.EmployeeRef.builder()
                            .id(caretaker.getEmployeeId()).name(caretaker.getName()).build())
                    .customer(customers.get(s.getPetId()))
                    .build();
        }).toList();
    }
}
