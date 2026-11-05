package com.petplatform.common.storage;

import com.petplatform.common.error.BusinessException;
import com.petplatform.common.error.CommonErrorCode;

/**
 * 头像 key 持久化前的实际对象闸（S10/C12）。
 *
 * <p>上传 intent 只校验客户端声明的 contentLength；presigned PUT 不绑定体积，
 * 所以"声明 68 字节、实际 PUT 5MiB+1"可以绕过 intent。真正把 key 写入用户/宠物资料前，
 * 必须先用本闸验证 key 归属、场景和对象实际字节数。
 *
 * <p>顺序固定：key 形态 → 桶主 → scene → 存储可用 → HEAD 实际大小。
 * 任何不满足都抛业务异常，调用方必须在数据库写入之前调用。
 */
public final class MediaObjectGuard {

    private MediaObjectGuard() {
    }

    public static void requireWritable(MediaStorage storage, String key, Long expectedOwner,
                                       String expectedScene, long maxBytes) {
        if (!MediaStorage.isValidKey(key)) {
            throw invalid("图片地址无效，请重新上传");
        }
        Long owner = MediaStorage.keyOwner(key);
        if (expectedOwner == null || owner == null || !expectedOwner.equals(owner)) {
            throw invalid("图片不属于当前账号，请重新上传");
        }
        if (!expectedScene.equals(MediaStorage.keyScene(key))) {
            throw invalid("图片场景不匹配，请重新上传");
        }
        if (storage == null) {
            throw new BusinessException(CommonErrorCode.SYSTEM_ERROR, "图片存储未启用，无法校验图片");
        }
        MediaStorage.StoredObject object;
        try {
            object = storage.stat(key);
        } catch (RuntimeException e) {
            throw new BusinessException(CommonErrorCode.SYSTEM_ERROR, "图片校验失败，请稍后重试");
        }
        if (object == null) {
            throw invalid("图片不存在，请重新上传");
        }
        if (object.size() <= 0) {
            throw invalid("图片为空，请重新上传");
        }
        if (object.size() > maxBytes) {
            throw invalid("图片超过 " + (maxBytes / 1024 / 1024) + "MB 限制");
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(CommonErrorCode.PARAM_INVALID, message);
    }
}
