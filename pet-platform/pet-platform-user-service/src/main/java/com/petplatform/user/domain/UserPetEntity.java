package com.petplatform.user.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.petplatform.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("usr_user_pet")
public class UserPetEntity extends BaseEntity {

    private Long userId;
    private Long petId;
    /** OWNER/FAMILY */
    private String relation;
    private Long invitedBy;
}
