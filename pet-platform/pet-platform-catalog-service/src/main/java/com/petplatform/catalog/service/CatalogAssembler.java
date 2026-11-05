package com.petplatform.catalog.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.petplatform.catalog.api.dto.ServiceView;
import com.petplatform.catalog.api.dto.SkillRef;
import com.petplatform.catalog.api.dto.StoreServiceView;
import com.petplatform.catalog.domain.ServiceEntity;
import com.petplatform.catalog.domain.ServiceSkillEntity;
import com.petplatform.catalog.domain.StoreServiceEntity;
import com.petplatform.catalog.mapper.ServiceMapper;
import com.petplatform.catalog.mapper.ServiceSkillMapper;
import com.petplatform.employee.api.SkillQueryApi;
import com.petplatform.employee.api.dto.SkillView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 出参装配器：批量取平台服务 + 技能声明 + 技能名，一次一页只发 3 条查询。
 *
 * <p>技能名必须走 employee-api——emp_skill 归 employee 所有，catalog 直查会破
 * 第二阶段 §3.2 依赖矩阵（ArchUnit 红灯）。
 */
@Component
public class CatalogAssembler {

    private final ServiceMapper serviceMapper;
    private final ServiceSkillMapper serviceSkillMapper;
    private final SkillQueryApi skillQueryApi;

    public CatalogAssembler(ServiceMapper serviceMapper, ServiceSkillMapper serviceSkillMapper,
                            SkillQueryApi skillQueryApi) {
        this.serviceMapper = serviceMapper;
        this.serviceSkillMapper = serviceSkillMapper;
        this.skillQueryApi = skillQueryApi;
    }

    public List<ServiceView> toServiceViews(List<ServiceEntity> services) {
        if (services.isEmpty()) {
            return List.of();
        }
        Map<Long, ServiceEntity> byId = services.stream()
                .collect(Collectors.toMap(ServiceEntity::getId, Function.identity()));
        Map<Long, List<SkillRef>> skills = skillsByService(byId.keySet());
        return services.stream().map(e -> toView(e, skills.getOrDefault(e.getId(), List.of()))).toList();
    }

    public ServiceView toServiceView(ServiceEntity entity) {
        Map<Long, List<SkillRef>> skills = skillsByService(List.of(entity.getId()));
        return toView(entity, skills.getOrDefault(entity.getId(), List.of()));
    }

    /** 门店上架服务视图：所需技能挂在平台服务层，故按 serviceId 取声明。 */
    public List<StoreServiceView> toStoreServiceViews(List<StoreServiceEntity> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }
        Set<Long> serviceIds = entities.stream()
                .map(StoreServiceEntity::getServiceId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, ServiceEntity> services = serviceMapper.selectBatchIds(serviceIds).stream()
                .collect(Collectors.toMap(ServiceEntity::getId, Function.identity()));
        Map<Long, List<SkillRef>> skills = skillsByService(serviceIds);
        List<StoreServiceView> views = new ArrayList<>(entities.size());
        for (StoreServiceEntity e : entities) {
            ServiceEntity s = services.get(e.getServiceId());
            if (s == null) {
                continue;   // 平台服务已删除：不在架视图里出现
            }
            boolean inherited = e.getAllowUserPickEmployee() == null;
            views.add(StoreServiceView.builder()
                    .id(e.getId())
                    .storeId(e.getStoreId())
                    .serviceId(s.getId())
                    .serviceCode(s.getCode())
                    .serviceName(s.getName())
                    .description(s.getDescription())
                    .category(s.getCategory())
                    .fulfillmentType(s.getFulfillmentType())
                    .price(e.getPrice())
                    .durationMin(e.getDurationMin())
                    .requiredResourceType(e.getRequiredResourceType())
                    .allowUserPickEmployee(inherited ? s.getAllowUserPickEmployee() : e.getAllowUserPickEmployee())
                    .allowUserPickEmployeeOverride(e.getAllowUserPickEmployee())
                    .cancelFreeHours(e.getCancelFreeHours())
                    .cancelPenaltyRate(e.getCancelPenaltyRate())
                    .status(e.getStatus())
                    .skills(skills.getOrDefault(s.getId(), List.of()))
                    .build());
        }
        return views;
    }

    private ServiceView toView(ServiceEntity e, List<SkillRef> refs) {
        return ServiceView.builder()
                .id(e.getId())
                .code(e.getCode())
                .name(e.getName())
                .description(e.getDescription())
                .category(e.getCategory())
                .requiredDurationMin(e.getRequiredDurationMin())
                .fulfillmentType(e.getFulfillmentType())
                .allowUserPickEmployee(e.getAllowUserPickEmployee())
                .status(e.getStatus())
                .skills(refs)
                .build();
    }

    /** serviceId → 技能引用列表；未知 skillId（emp_skill 已删）跳过但不影响其余装配。 */
    private Map<Long, List<SkillRef>> skillsByService(Collection<Long> serviceIds) {
        if (serviceIds.isEmpty()) {
            return Map.of();
        }
        List<ServiceSkillEntity> declarations = serviceSkillMapper.selectList(
                Wrappers.<ServiceSkillEntity>lambdaQuery().in(ServiceSkillEntity::getServiceId, serviceIds));
        if (declarations.isEmpty()) {
            return Map.of();
        }
        Set<Long> skillIds = declarations.stream()
                .map(ServiceSkillEntity::getSkillId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, SkillView> dict = new HashMap<>();
        for (SkillView sv : skillQueryApi.listByIds(skillIds)) {
            dict.put(sv.getId(), sv);
        }
        Map<Long, List<SkillRef>> result = new HashMap<>();
        for (ServiceSkillEntity d : declarations) {
            SkillView sv = dict.get(d.getSkillId());
            if (sv == null) {
                continue;
            }
            result.computeIfAbsent(d.getServiceId(), k -> new ArrayList<>()).add(SkillRef.builder()
                    .skillId(sv.getId())
                    .code(sv.getCode())
                    .name(sv.getName())
                    .required(Boolean.TRUE.equals(d.getRequired()))
                    .build());
        }
        return result;
    }
}
