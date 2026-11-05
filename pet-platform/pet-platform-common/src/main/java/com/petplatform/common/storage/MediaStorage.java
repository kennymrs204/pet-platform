package com.petplatform.common.storage;

import java.time.Duration;

/**
 * 媒体对象存储端口（ADR-029；AuthTokenApi 依赖倒置先例：接口在被全模块依赖的 common，
 * MinIO SDK 依赖与实现只在 app 装配层）。业务模块只存 key，签名统一走本端口。
 *
 * key 形态钉死：{@code u-{userId}/{SCENE}/{yyyyMM}/{雪花id}.{jpg|png|webp}}
 * （首段=每用户一桶，bucket-per-user，私有零匿名；presignGet 对不合形态的 key 拒签）。
 *
 * pet.storage.enabled=false 时实现 bean 不存在——消费方一律经 ObjectProvider 可选注入，
 * 视图签名字段回 null，业务不受影响。
 */
public interface MediaStorage {

    /** 读取签名默认时效（ADR-029 §契约3）。 */
    java.time.Duration GET_TTL = java.time.Duration.ofSeconds(600);

    /** 聊天图片场景段（上传 intent 与消息落库校验共用的单一常量源，P7.1）。 */
    String SCENE_CHAT_IMAGE = "CHAT_IMAGE";

    /**
     * 遛狗服务照片场景段（本轮新增）。与 CHAT_IMAGE 同为"业务场景"段：
     * 落库校验只认本段，签图出口不额外限制场景（读侧授权由持有记录的业务端点负责）。
     */
    String SCENE_WALK_PHOTO = "WALK_PHOTO";

    /**
     * 签发一次性上传凭证（presigned PUT）。惰性建用户桶。
     *
     * @param scene 已过白名单校验的场景段（大写）
     * @param ext   已由 contentType 反推的扩展名（jpg|png|webp）
     */
    UploadIntent presignUpload(long userId, String scene, String ext, Duration ttl);

    /** 签发读取 URL（presigned GET）；key 形态非法返回 null。 */
    String presignGet(String key, Duration ttl);

    /**
     * 对象元信息（HEAD 回查；消息发送侧"必有图可看"的写入闸用）。
     *
     * @return 对象存在 → size/contentType；不存在（NoSuchKey）或 key 形态非法 → null；
     *         存储基础设施异常 → IllegalStateException（调用方决定降级/报错）
     */
    StoredObject stat(String key);

    /** 对象元信息（size=字节数；contentType=PUT 时声明并被存储的值）。 */
    record StoredObject(long size, String contentType) {
    }

    java.util.regex.Pattern KEY_OWNER = java.util.regex.Pattern.compile("^u-(\\d{1,19})/");

    /**
     * key 完整形态（**单一正则源**）：签名出口（presignGet）与消息发送侧校验共用同一份——
     * 两处各持一份正则时，改形态/加场景漏改一处即"发送成功但永远签不出图"或反向。
     * 捕获组 1=场景段。
     */
    java.util.regex.Pattern KEY_SHAPE = java.util.regex.Pattern.compile(
            "^u-\\d{1,19}/([A-Z][A-Z_]*)/\\d{6}/\\d{1,20}\\.(jpg|png|webp)$");

    /** key 是否完全符合存储形态（null 一律 false）。 */
    static boolean isValidKey(String key) {
        return key != null && KEY_SHAPE.matcher(key).matches();
    }

    /** key 的场景段（形态非法返回 null）。 */
    static String keyScene(String key) {
        if (key == null) {
            return null;
        }
        java.util.regex.Matcher m = KEY_SHAPE.matcher(key);
        return m.matches() ? m.group(1) : null;
    }

    /**
     * 解析 key 归属用户（ADR-029 §契约4 越权防线）。形态不合返回 null。
     * 业务侧用它把"能否签名"约束成"key 属于本人或同一资源的相关人"——
     * presigned GET 一旦签发即等于桶内对象的可读凭证，所以自填 key 的出口必须校归属。
     */
    static Long keyOwner(String key) {
        if (key == null) {
            return null;
        }
        java.util.regex.Matcher m = KEY_OWNER.matcher(key);
        return m.find() ? Long.valueOf(m.group(1)) : null;
    }

    record UploadIntent(String key, String uploadUrl, long expireSeconds) {
    }
}
