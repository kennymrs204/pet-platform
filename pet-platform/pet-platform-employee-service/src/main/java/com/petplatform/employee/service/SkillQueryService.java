package com.petplatform.employee.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.petplatform.employee.api.SkillQueryApi;
import com.petplatform.employee.api.dto.SkillView;
import com.petplatform.employee.domain.SkillEntity;
import com.petplatform.employee.mapper.SkillMapper;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/** {@link SkillQueryApi} 实现：只读 emp_skill，供 catalog / schedule 跨模块消费。 */
@Service
public class SkillQueryService implements SkillQueryApi {

    private final SkillMapper skillMapper;

    public SkillQueryService(SkillMapper skillMapper) {
        this.skillMapper = skillMapper;
    }

    @Override
    public List<Long> filterExistingIds(Collection<Long> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) {
            return List.of();
        }
        return skillMapper.selectList(Wrappers.<SkillEntity>lambdaQuery()
                        .select(SkillEntity::getId)
                        .in(SkillEntity::getId, skillIds))
                .stream()
                .map(SkillEntity::getId)
                .toList();
    }

    @Override
    public List<SkillView> listByIds(Collection<Long> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) {
            return List.of();
        }
        return skillMapper.selectList(Wrappers.<SkillEntity>lambdaQuery()
                        .in(SkillEntity::getId, skillIds))
                .stream()
                .map(SkillQueryService::toView)
                .toList();
    }

    static SkillView toView(SkillEntity entity) {
        return SkillView.builder()
                .id(entity.getId())
                .code(entity.getCode())
                .name(entity.getName())
                .category(entity.getCategory())
                .build();
    }
}
