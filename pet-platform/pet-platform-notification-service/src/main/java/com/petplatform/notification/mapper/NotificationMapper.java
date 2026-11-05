package com.petplatform.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.petplatform.notification.domain.NotificationEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface NotificationMapper extends BaseMapper<NotificationEntity> {

    /**
     * 取行（ADR-041）。落在 {@link NotificationEntity} 而不是直接落视图：<b>收件人 user_id 必须止步于
     * service 层</b>——它映射成 NotificationView 时会被丢掉，前端因此拿不到任何收件人字段；
     * biz_ref 也在这里保持 JSON 原文，由 service 解析（MySQL 的 {@code ->>} 语法会把读侧绑死在库版本上）。
     * 这张表没有 deleted 列（append-only），所以没有 deleted=0。
     *
     * <p><b>列集合必须与 {@link NotificationEntity} 的字段一一对应</b>（有单测钉住这条形状不变式）。
     * ADR-047 加 {@code store_id} 时只改了写侧，读侧这行漏了列，于是查出来的实体里 storeId 恒为 null——
     * 当时没人读它所以没有症状，但这正是"下一个消费者以为拿到了真实值其实拿到 null"的伏笔
     * （可空包装类作判据时尤其危险，见 Mockito 对 Long 返 0L 不是 null 那条同类坑）。
     */
    String VIEW = "SELECT id, user_id, store_id, type, title, content, biz_ref, read_at, created_at FROM ntf_notification";

    @Select(VIEW + " WHERE user_id = #{userId} AND store_id = 0"
            + " ORDER BY created_at DESC, id DESC LIMIT #{pageSize} OFFSET #{offset}")
    List<NotificationEntity> selectForPersonalInbox(@Param("userId") Long userId,
                                                    @Param("pageSize") int pageSize,
                                                    @Param("offset") long offset);

    @Select("SELECT COUNT(*) FROM ntf_notification WHERE user_id = #{userId} AND store_id = 0")
    long countForPersonalInbox(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM ntf_notification"
            + " WHERE user_id = #{userId} AND store_id = 0 AND read_at IS NULL")
    long countUnreadForPersonalInbox(@Param("userId") Long userId);

    /**
     * 门店上下文里的消息（ADR-047，B 端收件箱）。排序与个人收件箱查询一致
     * （{@code created_at DESC, id DESC}）——同一盒消息在两端看到的次序不该不一样，
     * 而 {@code id} 兜底是必要的：同一次 fan-out 给多个员工写出的行 created_at 可以完全相等。
     */
    @Select(VIEW + " WHERE user_id = #{userId} AND store_id = #{storeId}"
            + " ORDER BY created_at DESC, id DESC LIMIT #{pageSize} OFFSET #{offset}")
    List<NotificationEntity> selectForUserInStore(@Param("userId") Long userId,
                                                 @Param("storeId") Long storeId,
                                                 @Param("pageSize") int pageSize,
                                                 @Param("offset") long offset);

    @Select("SELECT COUNT(*) FROM ntf_notification WHERE user_id = #{userId} AND store_id = #{storeId}")
    long countForUserInStore(@Param("userId") Long userId, @Param("storeId") Long storeId);

    /**
     * 门店收件箱未读数（H5·Package 1）：<b>排除 {@code CHAT_MESSAGE}</b>——聊天未读的真相是
     * conversation 的 store_last_read_message_id 游标（由 {@code storeUnreadTotal} 提供），
     * 聊天提醒只是消息中心里的提醒记录；不排除会在铃铛 total=notificationCount+chatCount 处双计。
     */
    @Select("SELECT COUNT(*) FROM ntf_notification WHERE user_id = #{userId} AND store_id = #{storeId}"
            + " AND read_at IS NULL AND type <> 'CHAT_MESSAGE'")
    long countUnreadInStore(@Param("userId") Long userId, @Param("storeId") Long storeId);

    /**
     * 门店<b>消息中心列表</b>的完整未读行数：{@code user_id + store_id + read_at IS NULL}，
     * <b>不排除 {@code CHAT_MESSAGE}</b>——因为消息列表（{@code selectForUserInStore}）本身就渲染这些行、
     * 且 {@code updateMarkAllReadInStore} 会把它们一并标读。列表未读数与「全部已读」按钮必须与
     * 这个更新范围一致，不能拿排除了 CHAT_MESSAGE 的铃铛 {@link #countUnreadInStore} 顶替。
     *
     * <p>与铃铛口径刻意分列两个方法：{@code countUnreadInStore}=通知未读（排除 chat，铃铛分账防与
     * chatCount 双计）；本方法=列表行未读（含 chat 提醒行）。两者语义不同，各用各的，不互相替换。
     */
    @Select("SELECT COUNT(*) FROM ntf_notification WHERE user_id = #{userId} AND store_id = #{storeId}"
            + " AND read_at IS NULL")
    long countUnreadAllTypesInStore(@Param("userId") Long userId, @Param("storeId") Long storeId);

    /** 个人收件箱归属探针：id、收件人与 store_id=0 同时命中才算这条个人消息是你的。 */
    @Select("SELECT COUNT(*) FROM ntf_notification"
            + " WHERE id = #{id} AND user_id = #{userId} AND store_id = 0")
    long countOwnedPersonal(@Param("userId") Long userId, @Param("id") Long id);

    /** 门店收件箱归属探针：id、收件人与当前门店同时命中才算这条消息属于该门店上下文。 */
    @Select("SELECT COUNT(*) FROM ntf_notification"
            + " WHERE id = #{id} AND user_id = #{userId} AND store_id = #{storeId}")
    long countOwnedInStore(@Param("userId") Long userId, @Param("storeId") Long storeId,
                           @Param("id") Long id);

    /**
     * 幂等插入（ADR-045）：同一 {@code (user_id, store_id, type, biz_ref.module, biz_ref.bizId)}
     * 已存在时一行也不写。
     *
     * <p>用条件 INSERT 而不是"先查再插"：后者在并发下两条都能过检查，前者把判定交给数据库的一条语句。
     * 也刻意<b>不加唯一索引</b>——那要改已建表（新迁移），而本表允许"同类型同业务多行"的合法场景
     * （退款申请→被拒→再申请，见 ADR-043 探针实测序列），唯一索引会把它一起禁掉。
     *
     * <p>ADR-047 把 {@code store_id} 加进去重键的理由：同一个人可以既是某店的员工、又是客户。
     * 他在自己店里下一单，会同时产生"他个人的下单成功消息"（store_id=0）和将来可能有的门店侧消息；
     * 两个收件箱里的同语义行<b>不是重复</b>，按收件箱判等才不会把其中一个静默吞掉。
     *
     * @return 0=幂等命中（已有同语义通知）；1=本次真落了一条
     */
    @Insert("INSERT INTO ntf_notification (id, user_id, store_id, type, title, content, biz_ref)"
            + " SELECT #{e.id}, #{e.userId}, #{e.storeId}, #{e.type}, #{e.title}, #{e.content},"
            + " CAST(#{e.bizRef} AS JSON) FROM DUAL"
            + " WHERE NOT EXISTS (SELECT 1 FROM ntf_notification"
            + " WHERE user_id = #{e.userId} AND store_id = #{e.storeId} AND type = #{e.type}"
            + " AND JSON_UNQUOTE(JSON_EXTRACT(biz_ref, '$.module')) = #{module}"
            + " AND JSON_UNQUOTE(JSON_EXTRACT(biz_ref, '$.bizId')) = #{bizId})")
    int insertIfAbsent(@Param("e") NotificationEntity entity, @Param("module") String module,
                       @Param("bizId") String bizId);

    /**
     * 个人收件箱单条已读：UPDATE 自身带 {@code store_id = 0}，不能只靠先查所有权。
     * 0 行=已读（幂等）或不属于个人收件箱；调用方用 {@link #countOwnedPersonal} 分辨。
     */
    @Update("UPDATE ntf_notification SET read_at = CURRENT_TIMESTAMP(3)"
            + " WHERE id = #{id} AND user_id = #{userId} AND store_id = 0 AND read_at IS NULL")
    int updateMarkReadPersonal(@Param("userId") Long userId, @Param("id") Long id);

    /** 门店收件箱单条已读：UPDATE 自身带当前门店，跨店 id 不能命中。 */
    @Update("UPDATE ntf_notification SET read_at = CURRENT_TIMESTAMP(3)"
            + " WHERE id = #{id} AND user_id = #{userId} AND store_id = #{storeId}"
            + " AND read_at IS NULL")
    int updateMarkReadInStore(@Param("userId") Long userId, @Param("storeId") Long storeId,
                              @Param("id") Long id);

    /** 个人收件箱全部已读：只动 {@code user_id + store_id=0} 的未读行。 */
    @Update("UPDATE ntf_notification SET read_at = CURRENT_TIMESTAMP(3)"
            + " WHERE user_id = #{userId} AND store_id = 0 AND read_at IS NULL")
    int updateMarkAllReadPersonal(@Param("userId") Long userId);

    /**
     * 全部已读（门店上下文，ADR-047）：只动<b>这个人这家店</b>的未读行。
     * 少一个 {@code store_id} 条件就是跨上下文副作用——在 9111 点一下会把 9112 的提醒
     * 和他作为客户的个人消息一起标读，而用户从没要求过这件事。
     */
    @Update("UPDATE ntf_notification SET read_at = CURRENT_TIMESTAMP(3)"
            + " WHERE user_id = #{userId} AND store_id = #{storeId} AND read_at IS NULL")
    int updateMarkAllReadInStore(@Param("userId") Long userId, @Param("storeId") Long storeId);

    /**
     * H5：读会话联动——把该收件人在该门店下、biz_ref 指向该 conversation 的未读 CHAT_MESSAGE
     * 提醒全部标已读（幂等；0 行=本就没有未读的该会话提醒）。
     * 不按单个 bizId 清：T1 语义下同一会话只可能有"未读段首条"级的一条未读提醒，但历史累积
     * （读前多段）必须一次清干净，否则消息中心残留已读会话的未读提醒。
     */
    @Update("UPDATE ntf_notification SET read_at = CURRENT_TIMESTAMP(3)"
            + " WHERE user_id = #{userId} AND store_id = #{storeId} AND type = 'CHAT_MESSAGE'"
            + " AND read_at IS NULL"
            + " AND JSON_UNQUOTE(JSON_EXTRACT(biz_ref, '$.module')) = 'chat'"
            + " AND JSON_UNQUOTE(JSON_EXTRACT(biz_ref, '$.conversationId')) = #{conversationId}")
    int markChatRemindersRead(@Param("userId") Long userId, @Param("storeId") Long storeId,
                              @Param("conversationId") Long conversationId);
}
