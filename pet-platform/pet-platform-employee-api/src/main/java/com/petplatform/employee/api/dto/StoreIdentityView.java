package com.petplatform.employee.api.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 员工在单个门店的 ACTIVE 归属摘要（ADR-012，GET /auth/employee-identities 的组成块）。
 * storeName 为占位字段：表 Owner 归 store 模块（ADR-002），employee 段不读 sto_store；
 * 待 store 段（第二段）交付 StoreQueryApi 后由装配层补值，契约字段先定不改。
 */
@Data
@Builder
public class StoreIdentityView {

    private Long storeId;
    private String storeName;
    private Boolean isPrimary;
    /** 该员工×该店的角色码（emp_role.code）；无角色绑定时为空列表 */
    private List<String> roles;
}
