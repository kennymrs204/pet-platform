package com.petplatform.user.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TokenResponse {

    private String accessToken;
    private String refreshToken;
    /** access 有效期（秒），前端据此提前刷新 */
    private long accessExpiresIn;
    private Long userId;
}
