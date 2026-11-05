package com.petplatform.user.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.petplatform.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("usr_user")
public class UserEntity extends BaseEntity {

    private String phone;
    private String passwordHash;
    private String nickname;
    private String avatar;
    /** NORMAL/DISABLED */
    private String status;
    private LocalDateTime lastLoginAt;
}
