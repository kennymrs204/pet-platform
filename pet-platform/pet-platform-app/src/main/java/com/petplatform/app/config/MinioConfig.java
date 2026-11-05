package com.petplatform.app.config;

import com.petplatform.common.storage.MediaStorage;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * MinIO 客户端与 MediaStorage 装配（ADR-029）：SDK 依赖只进 app。
 * enabled=false ⇒ 本配置整体不装载，消费方 ObjectProvider 拿不到 bean、签名字段回 null（支付通道先例）。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "pet.storage.enabled", havingValue = "true")
public class MinioConfig {

    @Bean
    public MinioClient minioClient(@Value("${pet.storage.endpoint}") String endpoint,
                                   @Value("${pet.storage.access-key}") String accessKey,
                                   @Value("${pet.storage.secret-key}") String secretKey) {
        return MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
    }

    /**
     * 签发客户端：presigned URL 的 host 参与 SigV4，必须等于**调用方实际访问的 host**。
     * endpoint（容器网名 minio:9000）只有本服务可达，浏览器拿到会解析失败，故 external-endpoint
     * 非空时另建一个只用于签名的 client；桶存在性/创建仍走内网 client。
     *
     * <p>{@code region} 必须显式给：SDK 在签名前会先向"要签的那个 host"发一次 GetBucketLocation 探 region，
     * 而 external-endpoint 是给浏览器读的（{@code localhost}）——容器里的 localhost 指容器自身，探测必失败，
     * presign 直接抛异常。这正是 .env 一度填 {@code host.docker.internal} 的唯一原因，
     * 而那个 host 在浏览器里经系统代理会 502（图片全裂、直传 PUT 失败）。给了 region 就不发那次请求，
     * 于是"浏览器可达"和"容器可签"第一次不再互斥。
     */
    @Bean
    public MediaStorage mediaStorage(
            MinioClient client,
            @Value("${pet.storage.endpoint}") String endpoint,
            @Value("${pet.storage.external-endpoint:}") String externalEndpoint,
            @Value("${pet.storage.access-key}") String accessKey,
            @Value("${pet.storage.secret-key}") String secretKey,
            @Value("${pet.storage.region:us-east-1}") String region) {
        MinioClient signer = client;
        if (!externalEndpoint.isBlank() && !externalEndpoint.equals(endpoint)) {
            signer = MinioClient.builder().endpoint(externalEndpoint).credentials(accessKey, secretKey)
                    .region(region).build();
        }
        return new MinioMediaStorage(client, signer);
    }

    /**
     * key 规范：u-{userId}/{SCENE}/{yyyyMM}/{雪花}.{jpg|png|webp}。
     * presignGet 只认这一形态（伪造/半截 key 一律拒签返回 null）；
     * 形态正则是 **MediaStorage.KEY_SHAPE 单一源**（消息发送侧校验共用，防两处漂移）。
     */
    @Slf4j
    static class MinioMediaStorage implements MediaStorage {

        private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");

        private final MinioClient client;
        private final MinioClient signer;

        MinioMediaStorage(MinioClient client, MinioClient signer) {
            this.client = client;
            this.signer = signer;
        }

        @Override
        public UploadIntent presignUpload(long userId, String scene, String ext, Duration ttl) {
            String bucket = "u-" + userId;
            ensureBucket(bucket);
            String object = scene + "/" + LocalDate.now().format(YM) + "/" + IdWorker.getId() + "." + ext;
            String url = presign(Method.PUT, bucket, object, ttl);
            return new UploadIntent(bucket + "/" + object, url, ttl.toSeconds());
        }

        @Override
        public String presignGet(String key, Duration ttl) {
            if (!MediaStorage.isValidKey(key)) {
                return null;
            }
            int slash = key.indexOf('/');
            return presign(Method.GET, key.substring(0, slash), key.substring(slash + 1), ttl);
        }

        /** HEAD 回查（走内网 client）：NoSuchKey/形态非法 → null；其余异常上抛由调用方归因。 */
        @Override
        public StoredObject stat(String key) {
            if (!MediaStorage.isValidKey(key)) {
                return null;
            }
            int slash = key.indexOf('/');
            try {
                io.minio.StatObjectResponse s = client.statObject(io.minio.StatObjectArgs.builder()
                        .bucket(key.substring(0, slash))
                        .object(key.substring(slash + 1))
                        .build());
                return new StoredObject(s.size(), s.contentType());
            } catch (io.minio.errors.ErrorResponseException e) {
                if ("NoSuchKey".equals(e.errorResponse().code())) {
                    return null;
                }
                throw new IllegalStateException("对象回查失败: " + key, e);
            } catch (Exception e) {
                throw new IllegalStateException("对象回查失败: " + key, e);
            }
        }

        private synchronized void ensureBucket(String bucket) {
            try {
                if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                    client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                }
            } catch (Exception e) {
                throw new IllegalStateException("用户桶初始化失败: " + bucket, e);
            }
        }

        private String presign(Method method, String bucket, String object, Duration ttl) {
            try {
                return signer.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                        .method(method).bucket(bucket).object(object).expiry((int) ttl.getSeconds()).build());
            } catch (Exception e) {
                throw new IllegalStateException("presign 失败: " + bucket + "/" + object, e);
            }
        }
    }
}
