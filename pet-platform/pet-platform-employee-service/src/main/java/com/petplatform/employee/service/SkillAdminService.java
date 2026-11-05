package com.petplatform.employee.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.result.PageResult;
import com.petplatform.employee.api.dto.SkillQuery;
import com.petplatform.employee.api.dto.SkillUpsertRequest;
import com.petplatform.employee.api.dto.SkillView;
import com.petplatform.employee.domain.SkillEntity;
import com.petplatform.employee.mapper.SkillMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 技能字典维护（第八阶段 §4.5 /api/v1/admin/skills）。
 *
 * <p>表 emp_skill 归 employee 所有，故实现落在本模块；权限码沿用第一阶段矩阵的
 * {@code catalog:skill:manage}（能力名带 catalog 前缀但落点由表 Owner 决定，ADR-002 表前缀唯一 Owner）。
 */
@Service
public class SkillAdminService {

    private final SkillMapper skillMapper;

    public SkillAdminService(SkillMapper skillMapper) {
        this.skillMapper = skillMapper;
    }

    public PageResult<SkillView> page(SkillQuery query) {
        String keyword = query.getKeyword();
        Page<SkillEntity> page = skillMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                Wrappers.<SkillEntity>lambdaQuery()
                        .and(StringUtils.hasText(keyword), w -> w
                                .like(SkillEntity::getName, keyword)
                                .or().like(SkillEntity::getCode, keyword))
                        .eq(StringUtils.hasText(query.getCategory()), SkillEntity::getCategory, query.getCategory())
                        .orderByAsc(SkillEntity::getId));
        List<SkillView> list = page.getRecords().stream().map(SkillQueryService::toView).toList();
        return PageResult.of(page.getTotal(), query.getPageNum(), query.getPageSize(), list);
    }

    @Transactional
    public SkillView create(SkillUpsertRequest request) {
        requireCodeAvailable(request.getCode(), null);
        SkillEntity entity = new SkillEntity();
        entity.setCode(request.getCode());
        entity.setName(request.getName());
        entity.setCategory(request.getCategory());
        try {
            skillMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "技能编码已存在");
        }
        return SkillQueryService.toView(entity);
    }

    @Transactional
    public SkillView update(Long skillId, SkillUpsertRequest request) {
        SkillEntity entity = skillMapper.selectById(skillId);
        if (entity == null) {
            throw BusinessException.of(CommonErrorCode.NOT_FOUND);
        }
        requireCodeAvailable(request.getCode(), skillId);
        entity.setCode(request.getCode());
        entity.setName(request.getName());
        entity.setCategory(request.getCategory());
        try {
            skillMapper.updateById(entity);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "技能编码已存在");
        }
        return SkillQueryService.toView(entity);
    }

    private void requireCodeAvailable(String code, Long excludeId) {
        long same = skillMapper.selectCount(Wrappers.<SkillEntity>lambdaQuery()
                .eq(SkillEntity::getCode, code)
                .ne(excludeId != null, SkillEntity::getId, excludeId));
        if (same > 0) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "技能编码已存在");
        }
    }
}
