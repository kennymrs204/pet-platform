package com.petplatform.pet.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 健康档案（ADR-014）。疫苗明细/诊疗记录/处方/报告不属于 pet 域，不在本 DTO。
 * 空档案返回空数组而非 null（无数据≠报错）。
 */
@Data
@Builder
public class PetHealthVO {

    private Long petId;
    private List<String> allergies;
    private List<String> chronicDiseases;
    private String specialNotes;
    /** 后端写入的写入方主角色：CUSTOMER/DOCTOR/CARETAKER/ADMIN…，客户端不可传（ADR-001-H 溯源） */
    private String updatedByRole;
    /** 毫秒精度：同时作为下一次健康编辑的乐观并发令牌 */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss[.SSS]")
    private LocalDateTime updatedAt;
}
