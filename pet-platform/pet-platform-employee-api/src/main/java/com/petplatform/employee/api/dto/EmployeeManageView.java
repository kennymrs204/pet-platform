package com.petplatform.employee.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 员工写侧的统一回执（ADR-033）：把"这次到底改了什么"如实报给前端，而不是回一句"成功"。
 *
 * <p>{@code employeeCreated}/{@code affiliationCreated} 两个标记是这张回执的价值所在：
 * 店长纳编一个**别店已建过档**的人时，全局姓名/工号不会被覆盖（那是跨店共享字段，两店互改必打架），
 * 前端必须能区分"新建了员工档案 / 只是加进本店 / 本来就在本店"三种情形，否则店长不知道为什么要重填姓名。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeManageView {

    private Long employeeId;
    private Long userId;
    /** 当前生效的姓名：新建=来料；已建档=**库里那份**（不是本次传入值） */
    private String name;
    private String employeeNo;
    private Long storeId;
    /** true=本次新建 emp_employee 全局档案 */
    private boolean employeeCreated;
    /** true=本次新增或复活了本店归属 */
    private boolean affiliationCreated;
    /** 该员工当前是否以本店为主店 */
    private Boolean primaryStore;
    /** 落库后的本店角色 code（升序，与库内一致，前端以此为准刷新行） */
    private List<String> roleCodes;
    /** 落库后的技能 code（升序） */
    private List<String> skillCodes;
}
