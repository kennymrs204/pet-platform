package com.petplatform.boarding.service;

import com.petplatform.boarding.api.dto.KennelOptionView;
import com.petplatform.boarding.domain.BoardingStayEntity;
import com.petplatform.boarding.mapper.BoardingStayMapper;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.time.BusinessDates;
import com.petplatform.schedule.api.ResourceOccupancyApi;
import com.petplatform.schedule.api.dto.KennelResourceView;
import com.petplatform.schedule.api.dto.OccupancyView;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 入住选笼位的候选清单（ADR-018）。表 Owner 边界：笼位资源/占用一律经 ResourceOccupancyApi 读，
 * boarding 不直查 sch_* 表；本服务只做"按住单区间标可用性"的组合，不复制 checkin 的类型/体型规则
 * （那条规则只有 resolveKennel 一处，避免两处真相漂移——不匹配的笼位由后端 10001 权威拒绝）。
 */
@Service
public class KennelOptionService {

    /** 四类笼位（availableResources 要求逐类查询）；顺序稳定便于 UI 分组 */
    private static final List<String> KENNEL_TYPES =
            List.of("CAT_ROOM", "KENNEL_LARGE", "KENNEL_MEDIUM", "KENNEL_SMALL");

    private final BoardingStayMapper stayMapper;
    private final ResourceOccupancyApi occupancyApi;

    public KennelOptionService(BoardingStayMapper stayMapper, ResourceOccupancyApi occupancyApi) {
        this.stayMapper = stayMapper;
        this.occupancyApi = occupancyApi;
    }

    /**
     * stayId 给定 → 区间取该住单的计划入住/离店日（跨店与不存在统一 10004，防探测）；
     * 否则按 date（缺省业务今日）单日。
     */
    public List<KennelOptionView> options(Long storeId, Long stayId, LocalDate date) {
        LocalDate from;
        LocalDate toExclusive;
        Long ownAppointmentId = null;
        if (stayId != null) {
            BoardingStayEntity stay = stayMapper.selectById(stayId);
            if (stay == null || !stay.getStoreId().equals(storeId)) {
                throw BusinessException.of(CommonErrorCode.NOT_FOUND);
            }
            from = stay.getCheckinDate();
            toExclusive = stay.getCheckoutDate();
            if (from == null || toExclusive == null || !toExclusive.isAfter(from)) {
                throw new BusinessException(CommonErrorCode.PARAM_INVALID, "住单计划日期非法，无法筛选笼位");
            }
            ownAppointmentId = stay.getAppointmentId();
        } else {
            from = date != null ? date : BusinessDates.today();
            toExclusive = from.plusDays(1);
        }

        Set<Long> occupied = new HashSet<>(occupancyApi.kennelIdsOccupiedBetween(storeId, from, toExclusive));
        // 本预约自己的预占行不算冲突：否则「沿用预占」那只笼位会在下拉里显示成已被占用
        if (ownAppointmentId != null) {
            for (OccupancyView o : occupancyApi.occupancyOfAppointment(ownAppointmentId)) {
                if (!o.getWorkDate().isBefore(from) && o.getWorkDate().isBefore(toExclusive)) {
                    occupied.remove(o.getResourceId());
                }
            }
        }
        List<KennelOptionView> out = new ArrayList<>();
        for (String type : KENNEL_TYPES) {
            for (KennelResourceView r : occupancyApi.availableResources(storeId, type)) {
                out.add(KennelOptionView.builder()
                        .id(r.getId())
                        .type(r.getType())
                        .name(r.getName())
                        .spanFrom(from)
                        .spanToExclusive(toExclusive)
                        .freeForSpan(!occupied.contains(r.getId()))
                        .build());
            }
        }
        return out;
    }
}
