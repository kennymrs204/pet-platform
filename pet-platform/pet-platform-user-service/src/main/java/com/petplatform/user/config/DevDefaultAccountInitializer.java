package com.petplatform.user.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.petplatform.user.domain.UserEntity;
import com.petplatform.user.mapper.UserMapper;
import com.petplatform.user.service.CustomerProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 开发期默认账号种子：仅当配置 pet.dev.default-account=enabled 时生效
 * （application-local.yml / application-docker.yml 默认开启，可用 PET_DEV_DEFAULT_ACCOUNT 关闭）。
 * 幂等：手机号已存在则复用，并补齐客户档案——CUSTOMER 权限码依赖档案存在。
 */
@Component
@ConditionalOnProperty(prefix = "pet.dev", name = "default-account", havingValue = "enabled")
public class DevDefaultAccountInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDefaultAccountInitializer.class);

    private final UserMapper userMapper;
    private final CustomerProfileService customerProfileService;
    private final String phone;
    private final String password;

    public DevDefaultAccountInitializer(UserMapper userMapper,
                                        CustomerProfileService customerProfileService,
                                        @Value("${pet.dev.default-account-phone:13800000000}") String phone,
                                        @Value("${pet.dev.default-account-password:Pet@123456}") String password) {
        this.userMapper = userMapper;
        this.customerProfileService = customerProfileService;
        this.phone = phone;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        UserEntity existing = userMapper.selectOne(
                Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getPhone, phone));
        if (existing != null) {
            customerProfileService.ensureProfile(existing.getId());
            log.info("[DEV] 默认账号已存在 phone={}，已确保客户档案", phone);
            return;
        }
        UserEntity user = new UserEntity();
        user.setPhone(phone);
        user.setNickname("开发默认账号");
        user.setStatus("NORMAL");
        user.setPasswordHash(new BCryptPasswordEncoder().encode(password));
        userMapper.insert(user);
        customerProfileService.ensureProfile(user.getId());
        log.info("[DEV] 已创建默认账号 phone={}（仅开发环境）", phone);
    }
}
