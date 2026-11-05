package com.petplatform.catalog.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.petplatform.catalog.api.dto.ServiceQuery;
import com.petplatform.catalog.api.dto.ServiceUpsertRequest;
import com.petplatform.catalog.api.dto.ServiceView;
import com.petplatform.catalog.domain.ServiceEntity;
import com.petplatform.catalog.domain.ServiceSkillEntity;
import com.petplatform.catalog.mapper.ServiceMapper;
import com.petplatform.catalog.mapper.ServiceSkillMapper;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.result.PageResult;
import com.petplatform.employee.api.SkillQueryApi;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 平台服务目录维护（第八阶段 §4.5 /api/v1/admin/services，ADMIN / PLATFORM_HQ 上下文）。
 *
 * <p>目录层无价格：定价只发生在门店上架层（第七阶段 §cat_service 注记）。
 */
@Service
public class AdminCatalogService {

    /** 时段槽位 30 分钟离散化（ADR-005-2），标准时长与门店时长都必须对齐。 */
    static final int SLOT_MIN = 30;

    private final ServiceMapper serviceMapper;
    private final ServiceSkillMapper serviceSkillMapper;
    private final SkillQueryApi skillQueryApi;
    private final CatalogAssembler assembler;

    public AdminCatalogService(ServiceMapper serviceMapper, ServiceSkillMapper serviceSkillMapper,
                               SkillQueryApi skillQueryApi, CatalogAssembler assembler) {
        this.serviceMapper = serviceMapper;
        this.serviceSkillMapper = serviceSkillMapper;
        this.skillQueryApi = skillQueryApi;
        this.assembler = assembler;
    }

    public PageResult<ServiceView> page(ServiceQuery query) {
        String keyword = query.getKeyword();
        Page<ServiceEntity> page = serviceMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                Wrappers.<ServiceEntity>lambdaQuery()
                        .and(StringUtils.hasText(keyword), w -> w
                                .like(ServiceEntity::getName, keyword)
                                .or().like(ServiceEntity::getCode, keyword))
                        .eq(StringUtils.hasText(query.getCategory()), ServiceEntity::getCategory, query.getCategory())
                        .eq(StringUtils.hasText(query.getStatus()), ServiceEntity::getStatus, query.getStatus())
                        .orderByAsc(ServiceEntity::getId));
        return PageResult.of(page.getTotal(), query.getPageNum(), query.getPageSize(),
                assembler.toServiceViews(page.getRecords()));
    }

    @Transactional
    public ServiceView create(ServiceUpsertRequest request) {
        requireDurationAligned(request.getRequiredDurationMin());
        requireCodeAvailable(request.getCode(), null);
        ServiceEntity entity = new ServiceEntity();
        applyTo(entity, request);
        entity.setStatus(StringUtils.hasText(request.getStatus()) ? request.getStatus() : "ON");
        try {
            serviceMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "服务编码已存在");
        }
        replaceSkills(entity.getId(), request.getSkills());
        return assembler.toServiceView(entity);
    }

    @Transactional
    public ServiceView update(Long serviceId, ServiceUpsertRequest request) {
        ServiceEntity entity = serviceMapper.selectById(serviceId);
        if (entity == null) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        requireDurationAligned(request.getRequiredDurationMin());
        requireCodeAvailable(request.getCode(), serviceId);
        applyTo(entity, request);
        if (StringUtils.hasText(request.getStatus())) {
            entity.setStatus(request.getStatus());
        }
        try {
            serviceMapper.updateById(entity);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "服务编码已存在");
        }
        replaceSkills(serviceId, request.getSkills());
        return assembler.toServiceView(entity);
    }

    private void applyTo(ServiceEntity entity, ServiceUpsertRequest request) {
        entity.setCode(request.getCode());
        entity.setName(request.getName());
        entity.setDescription(request.getDescription());
        entity.setCategory(request.getCategory());
        entity.setRequiredDurationMin(request.getRequiredDurationMin());
        entity.setFulfillmentType(request.getFulfillmentType());
        entity.setAllowUserPickEmployee(request.getAllowUserPickEmployee());
    }

    /** 全量替换技能声明（null=清空）；所需技能由预约侧 §C7 员工技能匹配消费。 */
    private void replaceSkills(Long serviceId, List<ServiceUpsertRequest.SkillDeclaration> declarations) {
        serviceSkillMapper.delete(Wrappers.<ServiceSkillEntity>lambdaQuery()
                .eq(ServiceSkillEntity::getServiceId, serviceId));
        if (declarations == null || declarations.isEmpty()) {
            return;
        }
        Set<Long> skillIds = new LinkedHashSet<>();
        for (ServiceUpsertRequest.SkillDeclaration d : declarations) {
            if (!skillIds.add(d.getSkillId())) {
                throw new BusinessException(CommonErrorCode.PARAM_INVALID, "技能声明重复");
            }
        }
        List<Long> existing = skillQueryApi.filterExistingIds(skillIds);
        if (existing.size() != skillIds.size()) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "技能不存在或已停用");
        }
        for (ServiceUpsertRequest.SkillDeclaration d : declarations) {
            ServiceSkillEntity row = new ServiceSkillEntity();
            row.setServiceId(serviceId);
            row.setSkillId(d.getSkillId());
            row.setRequired(d.getRequired() == null || d.getRequired());
            try {
                serviceSkillMapper.insert(row);
            } catch (DuplicateKeyException e) {
                throw new BusinessException(CommonErrorCode.PARAM_INVALID, "技能声明重复");
            }
        }
    }

    private void requireCodeAvailable(String code, Long excludeId) {
        long same = serviceMapper.selectCount(Wrappers.<ServiceEntity>lambdaQuery()
                .eq(ServiceEntity::getCode, code)
                .ne(excludeId != null, ServiceEntity::getId, excludeId));
        if (same > 0) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "服务编码已存在");
        }
    }

    private void requireDurationAligned(Integer durationMin) {
        if (durationMin % SLOT_MIN != 0) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "服务时长须为 30 分钟的整数倍");
        }
    }
}
