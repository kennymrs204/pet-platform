package com.petplatform.app.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件装配（app 是装配根，插件只装一次、全模块共享）。
 *
 * <p>PaginationInnerInterceptor 缺失时 {@code selectPage} 会静默退化成全表查询
 * （不拼 LIMIT、total 恒为 0），分页语义只能在此集中保证；
 * maxLimit 与 common 的 PageQuery.pageSize≤100 双保险。
 *
 * <p>BlockAttackInnerInterceptor 拦截无 where 的全表 update/delete——逻辑删除
 * （delval=id）会把 deleted 写成整表 id，属于必须拦住的事故。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(100L);
        pagination.setOverflow(false);   // 页码越界返回空集，不回绕到首页
        interceptor.addInnerInterceptor(pagination);
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());
        return interceptor;
    }
}
