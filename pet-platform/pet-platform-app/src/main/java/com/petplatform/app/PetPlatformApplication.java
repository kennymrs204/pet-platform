package com.petplatform.app;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 唯一可执行入口（模块化单体）。聚合装配 15 个业务 service 模块
 * （`medical`/`recruitment` 目前为空壳），
 * Mapper 扫描覆盖各模块 mapper 包。事件链单向性由 ArchUnit 固化。
 */
@SpringBootApplication(scanBasePackages = "com.petplatform")
@ConfigurationPropertiesScan("com.petplatform")
@MapperScan("com.petplatform.**.mapper")
@EnableScheduling
public class PetPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(PetPlatformApplication.class, args);
    }
}
