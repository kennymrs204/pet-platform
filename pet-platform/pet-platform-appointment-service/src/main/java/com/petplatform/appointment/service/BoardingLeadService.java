package com.petplatform.appointment.service;

import com.petplatform.appointment.api.BoardingLeadQueryApi;
import com.petplatform.appointment.api.dto.BoardingLeadView;
import com.petplatform.appointment.mapper.BoardingLeadMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/** ADR-030：只读，无事务、无副作用；入参钳制在这里收口（调用方传脏值不会打全表）。 */
@Service
public class BoardingLeadService implements BoardingLeadQueryApi {

    /** 兜底行数上限：门店量级小（DAU≈300），本看板不做分页 */
    private static final int MAX_LIMIT = 100;

    private final BoardingLeadMapper mapper;

    public BoardingLeadService(BoardingLeadMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<BoardingLeadView> pendingBoardingAppointments(Long storeId, LocalDate from, LocalDate to, int limit) {
        if (storeId == null || storeId == 0L || from == null || to == null || to.isBefore(from)) {
            return List.of();
        }
        return mapper.selectPending(storeId, from, to, Math.min(Math.max(limit, 1), MAX_LIMIT));
    }
}
