package com.petplatform.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.petplatform.user.domain.UserEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {

    /** 资料编辑专用全量写（ADR-027）：nickname 已由服务层归一（null→''），avatar 可空。 */
    @org.apache.ibatis.annotations.Update("UPDATE usr_user SET nickname = #{nickname}, avatar = #{avatar}"
            + " WHERE id = #{userId} AND deleted = 0")
    int updateNicknameAvatar(@org.apache.ibatis.annotations.Param("userId") Long userId,
                             @org.apache.ibatis.annotations.Param("nickname") String nickname,
                             @org.apache.ibatis.annotations.Param("avatar") String avatar);

    /** 门店侧改档定点写（ADR-058）：只动 nickname，不碰 avatar/status/phone（避开全量写覆盖）。 */
    @org.apache.ibatis.annotations.Update("UPDATE usr_user SET nickname = #{nickname}"
            + " WHERE id = #{userId} AND deleted = 0")
    int updateNickname(@org.apache.ibatis.annotations.Param("userId") Long userId,
                       @org.apache.ibatis.annotations.Param("nickname") String nickname);

    /** 手机号查账号 id（ADR-033 纳编）。未注册/已删返回 null；uk_phone 保证至多一行。 */
    @org.apache.ibatis.annotations.Select("SELECT id FROM usr_user WHERE phone = #{phone} AND deleted = 0 LIMIT 1")
    Long selectIdByPhone(@org.apache.ibatis.annotations.Param("phone") String phone);

    /**
     * 昵称/手机号包含匹配取 id，**keyset 游标**（{@code id > afterId} + {@code ORDER BY id ASC}），
     * 调用方循环到不满一批为止 → 结果集完整。{@code kw} 必须是**已转义**字面量
     * （{@code LikePattern.literal}）+ {@code ESCAPE '!'}，否则用户输入的 % 会退化成通配符。
     */
    @org.apache.ibatis.annotations.Select("""
            <script>
            SELECT u.id FROM usr_user u
            WHERE u.deleted = 0
              AND (u.nickname LIKE CONCAT('%', #{kw}, '%') ESCAPE '!'
                   OR u.phone LIKE CONCAT('%', #{kw}, '%') ESCAPE '!')
            <if test="afterId != null"> AND u.id &gt; #{afterId} </if>
            ORDER BY u.id ASC LIMIT #{limit}
            </script>
            """)
    java.util.List<Long> selectIdsByNicknameOrPhoneLike(@org.apache.ibatis.annotations.Param("kw") String kw,
                                                        @org.apache.ibatis.annotations.Param("afterId") Long afterId,
                                                        @org.apache.ibatis.annotations.Param("limit") int limit);
}
