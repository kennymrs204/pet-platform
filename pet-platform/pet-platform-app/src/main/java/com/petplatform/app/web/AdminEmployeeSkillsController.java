package com.petplatform.app.web;

import com.petplatform.common.result.Result;
import com.petplatform.employee.api.EmployeeManageApi;
import com.petplatform.employee.api.dto.EmployeeManageView;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台侧调整员工技能（ADR-051，补 ADR-040 留下的写权悬空口子）。
 *
 * <p>ADR-040 把技能写权收归"主店店长"（防跨店互吞），但漏了一种真实形态：**主店本身可能是
 * 没有店长的总部虚拟店**（实测：员工主店=id=1，该店只有 ADMIN/GROOMER 角色，无人能以店长身份
 * 持 {@code emp:skill:assign} 落到这家店的语境）→ 这名员工的技能全系统无人能改。
 * 隐藏出路是"ADMIN 代入总部店走 /store 端点"（ADR-040 §6 查证过的既定设计），但 UI 没有任何引导，
 * 代入其他门店时依旧 10003——等价于"管理员也给不了"。
 *
 * <p>本端点把这条隐藏路径扶正：闸 {@code store:manage}（仅 ADMIN），服务层以**员工主店**为语境
 * 复用既有替换逻辑（ADR-040 守卫对主店自己恒放行）；主店为空时 fail-open 直接替换。
 * 任何代入店上下文调用均可——技能写权跟着主店走，不跟着屏幕上的门店走。
 * 技能不进权限码集，故无需 {@code PermissionAssembler.evict}。
 */
@RestController
@RequestMapping("/api/v1/admin/employees")
public class AdminEmployeeSkillsController {

    private final EmployeeManageApi manageApi;

    public AdminEmployeeSkillsController(EmployeeManageApi manageApi) {
        this.manageApi = manageApi;
    }

    /** 全量替换技能集合（PUT 全量语义同店长端点；codes 空数组=清空）。 */
    @PutMapping("/{id}/skills")
    @PreAuthorize("hasAuthority('perm:store:manage')")
    public Result<EmployeeManageView> replaceSkills(@PathVariable Long id,
                                                    @Valid @RequestBody com.petplatform.employee.api.dto.AssignRequest request) {
        return Result.ok(manageApi.adminReplaceSkills(id, request.getCodes()));
    }
}
