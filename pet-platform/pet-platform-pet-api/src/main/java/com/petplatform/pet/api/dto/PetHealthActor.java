package com.petplatform.pet.api.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 健康档案写入操作人（由服务端装配层从登录主体构造，客户端不可传）。
 * operatorId 的语义由 operatorType 区分：EMPLOYEE=员工 id，CUSTOMER/ADMIN=登录用户 id。
 */
@Data
@Builder
public class PetHealthActor {

    /** CUSTOMER/EMPLOYEE/ADMIN */
    private String operatorType;
    private Long operatorId;
    /** 写入时姓名快照；查无为空 */
    private String operatorName;
    /** 写入时主角色码：CUSTOMER/DOCTOR/CARETAKER/ADMIN/… */
    private String operatorRole;
    /** B 端门店上下文；C 端自助修改为空 */
    private Long storeId;
}
