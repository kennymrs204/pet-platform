package com.petplatform.app.service;

import com.petplatform.common.storage.MediaStorage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 聊天图片签名（P7.1）：出参阶段把消息行的 mediaKey 签成 presigned GET URL。
 *
 * <p><b>唯一调用位点</b>：两端消息视图装配（CustomerChatController / StoreChatController 的 toView），
 * 且只在 message-service 的 ChatGuard 放行之后——不建通用 key 签名端点
 * （GET /media/sign?key=… 正是 ADR-029 §契约3 明确禁止的越权温床）。
 *
 * <p>降级口径同 StoreCustomerQueryService.presign：未启用存储（pet.storage.enabled=false）
 * 或签名异常 → null（前端按占位处理）——"签名是展示的锦上添花，不是业务数据"，
 * 不让一个图片的签名问题炸掉整页消息。非法形态 key 由 presignGet 拒签返回 null。
 */
@Component
public class ChatMediaSigner {

    private final ObjectProvider<MediaStorage> media;

    public ChatMediaSigner(ObjectProvider<MediaStorage> media) {
        this.media = media;
    }

    /** TEXT 消息 mediaKey=null → null；其余见类注释。 */
    public String sign(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        MediaStorage storage = media.getIfAvailable();
        if (storage == null) {
            return null;
        }
        try {
            return storage.presignGet(key, MediaStorage.GET_TTL);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
