package com.petplatform.user.web;

import com.petplatform.common.result.Result;
import com.petplatform.user.api.dto.SmsCodeRequest;
import com.petplatform.user.service.SmsCodeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开端点（第八阶段 §4.1，permitAll）：短信验证码发送（ADR-011：scene 隔离，缺省 LOGIN），
 * 限流同场景同手机号 60s/次、日上限 10 全局（SmsCodeService 内实现）。
 */
@RestController
@RequestMapping("/api/v1/public")
public class PublicSmsController {

    private final SmsCodeService smsCodeService;

    public PublicSmsController(SmsCodeService smsCodeService) {
        this.smsCodeService = smsCodeService;
    }

    @PostMapping("/sms-code")
    public Result<Void> sendSmsCode(@Valid @RequestBody SmsCodeRequest request) {
        smsCodeService.send(request.getPhone(), request.getScene());
        return Result.ok();
    }
}
