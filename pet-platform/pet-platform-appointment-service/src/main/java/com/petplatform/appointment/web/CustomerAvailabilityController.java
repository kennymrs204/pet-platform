package com.petplatform.appointment.web;

import com.petplatform.appointment.api.dto.AvailabilityView;
import com.petplatform.appointment.service.AppointmentAvailabilityService;
import com.petplatform.common.result.Result;
import com.petplatform.common.security.CurrentUserHolder;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * C 端可约时段查询（第八阶段 §4.2 GET /customer/store-services/{id}/availability，ADR-016 §7）。
 * 与创建共用 AvailabilityService——同一套资源/排班规则。只读无副作用。
 */
@RestController
@RequestMapping("/api/v1/customer/store-services")
public class CustomerAvailabilityController {

    private final AppointmentAvailabilityService availabilityService;

    public CustomerAvailabilityController(AppointmentAvailabilityService availabilityService) {
        this.availabilityService = availabilityService;
    }

    @GetMapping("/{id}/availability")
    @PreAuthorize("hasAuthority('perm:availability:view')")
    public Result<AvailabilityView> availability(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Long petId) {
        return Result.ok(availabilityService.availability(
                CurrentUserHolder.require().userId(), id, date, petId));
    }
}
