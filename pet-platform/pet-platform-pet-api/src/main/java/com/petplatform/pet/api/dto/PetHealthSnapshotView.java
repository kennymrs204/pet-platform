package com.petplatform.pet.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 健康档案三字段快照（变更日志的前后值；空列表表示未填写）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PetHealthSnapshotView {

    private List<String> allergies;
    private List<String> chronicDiseases;
    private String specialNotes;
}
