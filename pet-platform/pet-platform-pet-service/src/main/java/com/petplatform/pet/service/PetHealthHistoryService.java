package com.petplatform.pet.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.pet.api.dto.PetHealthChangeView;
import com.petplatform.pet.api.dto.PetHealthSnapshotView;
import com.petplatform.pet.domain.PetHealthChangeLogEntity;
import com.petplatform.pet.mapper.PetHealthChangeLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * B 端健康档案变更历史（只读）。
 *
 * <p>数据只来自 append-only 的 pet_health_change_log，绝不根据当前档案推导旧值。
 * 可见性先复用 {@link StorePetQueryService#requireVisible(Long, AuthPrincipal, Long)}：
 * 当前门店/任务范围内不可见的宠物统一 20101，历史行不会绕过宠物范围泄露。
 *
 * <p><b>当前口径是宠物级完整历史</b>：不按日志行的 store_id 二次过滤，因此同一只宠物
 * 被 C 端或其他门店修改的记录也会返回。是否改为“只显示本门店产生的历史”属于产品决策，
 * 改动会牺牲跨店护理连续性；本轮不擅自改变。
 *
 * <p>历史最多返回最近 {@value #HISTORY_LIMIT} 条；本批次不提供分页。日志保留策略见迁移注释：
 * 随宠物存续保留，逻辑删除宠物不级联删除日志，本轮无自动清理任务。
 */
@Slf4j
@Service
public class PetHealthHistoryService {

    static final int HISTORY_LIMIT = 50;

    private final StorePetQueryService storePetQueryService;
    private final PetHealthChangeLogMapper changeLogMapper;
    private final ObjectMapper json;

    public PetHealthHistoryService(StorePetQueryService storePetQueryService,
                                   PetHealthChangeLogMapper changeLogMapper, ObjectMapper json) {
        this.storePetQueryService = storePetQueryService;
        this.changeLogMapper = changeLogMapper;
        this.json = json;
    }

    public List<PetHealthChangeView> list(Long storeId, AuthPrincipal principal, Long petId) {
        storePetQueryService.requireVisible(storeId, principal, petId);
        return changeLogMapper.selectList(Wrappers.<PetHealthChangeLogEntity>lambdaQuery()
                        .eq(PetHealthChangeLogEntity::getPetId, petId)
                        .orderByDesc(PetHealthChangeLogEntity::getCreatedAt)
                        .orderByDesc(PetHealthChangeLogEntity::getId)
                        .last("LIMIT " + HISTORY_LIMIT))
                .stream().map(this::toView).toList();
    }

    private PetHealthChangeView toView(PetHealthChangeLogEntity entity) {
        return PetHealthChangeView.builder()
                .id(entity.getId())
                .changedAt(entity.getCreatedAt())
                .operatorType(entity.getOperatorType())
                .operatorId(entity.getOperatorId())
                .operatorName(entity.getOperatorName())
                .operatorRole(entity.getOperatorRole())
                .changedFields(readFields(entity.getChangedFields()))
                .before(readSnapshot(entity.getBeforeJson()))
                .after(readSnapshot(entity.getAfterJson()))
                .build();
    }

    private List<String> readFields(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            List<String> fields = json.readValue(raw, new TypeReference<List<String>>() {
            });
            return fields == null ? List.of() : fields;
        } catch (Exception e) {
            // 日志结构损坏不阻断整个历史列表；该行仍保留时间/操作人。
            log.warn("健康档案变更字段解析失败，降级空数组: {}", e.getMessage());
            return List.of();
        }
    }

    private PetHealthSnapshotView readSnapshot(String raw) {
        if (raw == null || raw.isBlank()) {
            return emptySnapshot();
        }
        try {
            PetHealthSnapshotView view = json.readValue(raw, PetHealthSnapshotView.class);
            if (view == null) {
                return emptySnapshot();
            }
            return PetHealthSnapshotView.builder()
                    .allergies(view.getAllergies() == null ? List.of() : view.getAllergies())
                    .chronicDiseases(view.getChronicDiseases() == null ? List.of() : view.getChronicDiseases())
                    .specialNotes(view.getSpecialNotes())
                    .build();
        } catch (Exception e) {
            log.warn("健康档案变更快照解析失败，降级空快照: {}", e.getMessage());
            return emptySnapshot();
        }
    }

    private static PetHealthSnapshotView emptySnapshot() {
        return PetHealthSnapshotView.builder()
                .allergies(List.of())
                .chronicDiseases(List.of())
                .specialNotes(null)
                .build();
    }
}
