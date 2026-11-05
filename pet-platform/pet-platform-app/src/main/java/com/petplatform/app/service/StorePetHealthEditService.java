package com.petplatform.app.service;

import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.employee.api.EmployeeQueryApi;
import com.petplatform.employee.api.dto.EmployeeBrief;
import com.petplatform.pet.api.PetCommandApi;
import com.petplatform.pet.api.dto.PetHealthActor;
import com.petplatform.pet.api.dto.PetHealthUpdateRequest;
import com.petplatform.pet.api.dto.PetHealthVO;
import com.petplatform.pet.service.StorePetQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * B 端健康档案维护编排。
 *
 * <p>顺序：宠物可见性/门店范围（StorePetQueryService）→ 服务端登录主体构造操作人 →
 * pet 模块健康写入命令（同事务记录 before/after 日志）。客户端不提供 storeId/employeeId/角色；
 * 门店只取已校验的 X-Store-Id，操作人只取 AuthPrincipal。
 *
 * <p>健康写权限沿用现有 pet:health:manage（V2 矩阵：ADMIN/DOCTOR/CARETAKER），
 * 不新增权限码；店长当前只有 pet:health:view，故本入口对其保持只读。
 *
 * <p>与基础资料编辑不同，健康档案不要求宠物 OWNER 在本店客户集：医生/护理员只要
 * 该宠物落在自己的本店诊疗/照护任务范围（requireVisible）即可维护健康记录。
 */
@Slf4j
@Service
public class StorePetHealthEditService {

    /** 多角色员工写健康档时的主角色判定顺序（影响 updatedByRole 与历史展示）。 */
    private static final List<String> ROLE_PRIORITY =
            List.of("ADMIN", "DOCTOR", "CARETAKER", "STORE_MANAGER", "RECEPTIONIST", "GROOMER", "DOG_WALKER");

    private final StorePetQueryService storePetQueryService;
    private final PetCommandApi petCommandApi;
    private final EmployeeQueryApi employeeQueryApi;

    public StorePetHealthEditService(StorePetQueryService storePetQueryService, PetCommandApi petCommandApi,
                                     EmployeeQueryApi employeeQueryApi) {
        this.storePetQueryService = storePetQueryService;
        this.petCommandApi = petCommandApi;
        this.employeeQueryApi = employeeQueryApi;
    }

    public PetHealthVO update(Long storeId, AuthPrincipal principal, Long petId, PetHealthUpdateRequest request) {
        storePetQueryService.requireVisible(storeId, principal, petId);
        // 空请求体在全量覆盖语义下等于"清空全部健康字段"；B 端拒绝这种误发，
        // 清空必须显式提交空数组（allergies/chronicDiseases 至少非 null）。
        if (request.getAllergies() == null && request.getChronicDiseases() == null
                && request.getSpecialNotes() == null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "健康档案至少提交一个字段；清空请显式提交空数组");
        }
        return petCommandApi.updateHealthForActor(petId, actorOf(principal, storeId), request);
    }

    private PetHealthActor actorOf(AuthPrincipal principal, Long storeId) {
        if (!principal.isEmployee()) {
            // 平台 ADMIN 代入门店时可能没有该店员工身份；仍保留其登录用户 id 作为操作人。
            return PetHealthActor.builder()
                    .operatorType("ADMIN")
                    .operatorId(principal.userId())
                    .operatorRole("ADMIN")
                    .storeId(storeId)
                    .build();
        }
        EmployeeBrief brief = null;
        try {
            brief = employeeQueryApi.briefsInStore(List.of(principal.employeeId()), storeId)
                    .stream().findFirst().orElse(null);
        } catch (Exception e) {
            // 姓名/角色是审计附加值，员工查询失败不阻断已授权的健康写入。
            log.warn("健康档案变更读取员工身份失败，降级为无姓名 employeeId={}: {}",
                    principal.employeeId(), e.getMessage());
        }
        List<String> roles = brief == null || brief.getRoleCodes() == null ? List.of() : brief.getRoleCodes();
        return PetHealthActor.builder()
                .operatorType("EMPLOYEE")
                .operatorId(principal.employeeId())
                .operatorName(brief == null ? null : brief.getName())
                .operatorRole(primaryRole(roles))
                .storeId(storeId)
                .build();
    }

    static String primaryRole(List<String> roles) {
        for (String role : ROLE_PRIORITY) {
            if (roles.contains(role)) {
                return role;
            }
        }
        return roles.isEmpty() ? "EMPLOYEE" : roles.get(0);
    }
}
