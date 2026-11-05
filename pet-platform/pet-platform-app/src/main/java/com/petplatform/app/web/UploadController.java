package com.petplatform.app.web;

import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;
import com.petplatform.common.result.Result;
import com.petplatform.common.security.AuthPrincipal;
import com.petplatform.common.security.CurrentUserHolder;
import com.petplatform.common.storage.MediaStorage;
import com.petplatform.message.api.ChatConversationApi;
import com.petplatform.message.api.ChatSide;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 上传意图端点（ADR-029 §契约1，POST /api/v1/uploads/intent，登录即可——anyRequest.authenticated 兜底）：
 * 校验白名单后签发 presigned PUT，浏览器把本地图片直传进"自己的桶"。
 * pet.storage.enabled=false 时本控制器不装载（404）。
 *
 * <p>P7.1：CHAT_IMAGE 场景不只是 scene 白名单——必须携带 conversationId 并先过
 * <b>会话归属守卫</b>（复用 message-service 的 ChatGuard：C 端=本人会话；B 端=当前
 * X-Store-Id 会话且持 store:chat:send），否则"任何登录用户可得任意会话的上传凭证"。
 */
@RestController
@RequestMapping("/api/v1/uploads")
@ConditionalOnProperty(name = "pet.storage.enabled", havingValue = "true")
public class UploadController {

    private static final Map<String, String> SCENES = Map.of(
            "USER_AVATAR", "jpg", "PET_AVATAR", "jpg", "PET_ALBUM", "jpg", "CARE_PHOTO", "jpg",
            MediaStorage.SCENE_CHAT_IMAGE, "jpg",
            // 遛狗服务照片：与 CHAT_IMAGE 不同，本场景的上传凭证不绑业务对象——
            // 授权发生在"关联到哪条遛狗记录"的写侧（StoreWalkPhotoService），与聊天同一套归属校验。
            MediaStorage.SCENE_WALK_PHOTO, "jpg"); // ext 由 contentType 反推，此表仅场景白名单
    private static final Map<String, String> CT_EXT = Map.of(
            "image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");
    private static final Duration PUT_TTL = Duration.ofSeconds(600);

    private final ObjectProvider<MediaStorage> storage;
    private final StringRedisTemplate redis;
    private final ChatConversationApi chatConversationApi;
    private final long maxSize;

    public UploadController(ObjectProvider<MediaStorage> storage, StringRedisTemplate redis,
                            ChatConversationApi chatConversationApi,
                            @org.springframework.beans.factory.annotation.Value("${pet.storage.max-size-mb:5}") long maxSizeMb) {
        this.storage = storage;
        this.redis = redis;
        this.chatConversationApi = chatConversationApi;
        this.maxSize = maxSizeMb * 1024 * 1024;
    }

    @PostMapping("/intent")
    public Result<IntentView> intent(@Valid @RequestBody IntentRequest req) {
        if (!SCENES.containsKey(req.getScene())) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "不支持的上传场景");
        }
        AuthPrincipal principal = CurrentUserHolder.require();
        // CHAT_IMAGE：scene 白名单之外再叠一层会话级授权（actor → conversation → ChatGuard → 凭证）
        if (MediaStorage.SCENE_CHAT_IMAGE.equals(req.getScene())) {
            requireChatConversation(req.getConversationId(), principal);
        }
        String ext = CT_EXT.get(req.getContentType());
        if (ext == null) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "仅支持 jpg/png/webp 图片");
        }
        if (req.getContentLength() <= 0 || req.getContentLength() > maxSize) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "文件大小须大于0且不超过限制");
        }
        Long userId = principal.userId();
        String minute = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
        Long cnt = redis.opsForValue().increment("upload:cnt:" + userId + ":" + minute);
        if (cnt != null && cnt == 1L) {
            redis.expire("upload:cnt:" + userId + ":" + minute, Duration.ofMinutes(2));
        }
        if (cnt != null && cnt > 20) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "上传过于频繁，请稍后再试");
        }
        MediaStorage.UploadIntent ui = storage.getObject().presignUpload(userId, req.getScene(), ext, PUT_TTL);
        return Result.ok(new IntentView(ui.key(), ui.uploadUrl(), ui.expireSeconds()));
    }

    /**
     * CHAT_IMAGE 会话级上传授权：conversationId 必填（10001），会话归属经 ChatGuard 复核——
     * C 端（无门店上下文）按 customer_user_id 匹配本人 + chat:send；B 端（X-Store-Id 上下文）
     * 按 store_id 匹配本店 + store:chat:send。会话不存在/非本人 → 10004（同枚举防枚举）；
     * 无聊天权限 → 10003。返回会话 id 供调用方留痕（当前仅做校验，不参与 key 生成）。
     */
    private void requireChatConversation(String rawConversationId, AuthPrincipal principal) {
        if (rawConversationId == null || rawConversationId.isBlank()) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "CHAT_IMAGE 必须携带 conversationId");
        }
        if (!rawConversationId.matches("\\d{1,19}")) {
            throw new BusinessException(CommonErrorCode.PARAM_INVALID, "非法 conversationId");
        }
        Long conversationId = Long.valueOf(rawConversationId);
        Long storeCtx = principal.storeCtx();
        if (storeCtx != null && storeCtx > 0) {
            if (!principal.permissions().contains("store:chat:send")) {
                throw BusinessException.of(CommonErrorCode.FORBIDDEN);
            }
            chatConversationApi.conversation(conversationId, ChatSide.STORE, storeCtx);
        } else {
            if (!principal.permissions().contains("chat:send")) {
                throw BusinessException.of(CommonErrorCode.FORBIDDEN);
            }
            chatConversationApi.conversation(conversationId, ChatSide.CUSTOMER, principal.userId());
        }
    }

    @Data
    public static class IntentRequest {
        @NotBlank(message = "scene必填")
        // 场景白名单只在 SCENES 判一处：曾有两份正则，加场景漏改一处即整端点 10001
        private String scene;
        @NotBlank(message = "contentType必填")
        private String contentType;
        @NotNull(message = "contentLength必填")
        @Positive(message = "contentLength须为正")
        private Long contentLength;
        /** 仅 CHAT_IMAGE 必填：图片将发送到哪个会话（服务端按会话归属授权，前端不得自证权限） */
        private String conversationId;
    }

    public record IntentView(String key, String uploadUrl, long expireIn) {
    }
}
