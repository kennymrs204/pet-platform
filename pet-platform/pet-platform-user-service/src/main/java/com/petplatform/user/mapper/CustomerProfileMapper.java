package com.petplatform.user.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CustomerProfileMapper {

    @Select("SELECT COUNT(*) FROM usr_customer_profile WHERE user_id = #{userId} AND deleted = 0")
    long countByUserId(@Param("userId") Long userId);

    /** 批量取偏好门店、营销授权位、默认联系电话与备注（ADR-017 §6 / ADR-058；无档案的 userId 不入结果）。 */
    @Select("""
            <script>
            SELECT p.user_id, p.preferred_store_id, p.marketing_opt_in, p.default_contact_phone, p.remark
            FROM usr_customer_profile p
            WHERE p.deleted = 0 AND p.user_id IN
            <foreach collection="userIds" item="uid" open="(" separator="," close=")">#{uid}</foreach>
            </script>
            """)
    java.util.List<ProfileRow> selectByUserIds(@Param("userIds") java.util.Collection<Long> userIds);

    class ProfileRow {
        private Long userId;
        private Long preferredStoreId;
        private boolean marketingOptIn;
        private String defaultContactPhone;
        private String remark;

        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
        public Long getPreferredStoreId() { return preferredStoreId; }
        public void setPreferredStoreId(Long preferredStoreId) { this.preferredStoreId = preferredStoreId; }
        public boolean isMarketingOptIn() { return marketingOptIn; }
        public void setMarketingOptIn(boolean marketingOptIn) { this.marketingOptIn = marketingOptIn; }
        public String getDefaultContactPhone() { return defaultContactPhone; }
        public void setDefaultContactPhone(String v) { this.defaultContactPhone = v; }
        public String getRemark() { return remark; }
        public void setRemark(String v) { this.remark = v; }
    }

    /** 门店侧改档定点写（ADR-058）：只动 default_contact_phone 与 remark，不碰 preferred/marketing（避开全量写覆盖）。 */
    @org.apache.ibatis.annotations.Update("UPDATE usr_customer_profile SET default_contact_phone = #{contactPhone}, remark = #{remark}"
            + " WHERE user_id = #{userId} AND deleted = 0")
    int updateContactAndRemark(@Param("userId") Long userId, @Param("contactPhone") String contactPhone,
                               @Param("remark") String remark);

    /** 建档幂等（ADR-027：注册/GET 均 ensure；uk_user 兜底并发）。 */
    @org.apache.ibatis.annotations.Insert("INSERT IGNORE INTO usr_customer_profile"
            + " (id, user_id, marketing_opt_in) VALUES (#{id}, #{userId}, 1)")
    int insertIgnore(@Param("id") long id, @Param("userId") Long userId);

    /** 门店代客建档（ADR-057）：建档行带 origin_store_id；营销位沿用默认 1（与注册建档同口径）。 */
    @org.apache.ibatis.annotations.Insert("INSERT INTO usr_customer_profile"
            + " (id, user_id, origin_store_id, marketing_opt_in) VALUES (#{id}, #{userId}, #{originStoreId}, 1)")
    int insertWithOrigin(@Param("id") long id, @Param("userId") Long userId,
                         @Param("originStoreId") Long originStoreId);

    /** 本店建档的客户 user_id（ADR-057 口径第二来源；存量行 origin_store_id 为 NULL 天然不入）。 */
    @Select("SELECT user_id FROM usr_customer_profile WHERE deleted = 0 AND origin_store_id = #{storeId}")
    java.util.List<Long> selectUserIdsByOriginStore(@Param("storeId") Long storeId);

    @Select("SELECT p.user_id, p.default_contact_phone, p.preferred_store_id, p.marketing_opt_in"
            + " FROM usr_customer_profile p WHERE p.deleted = 0 AND p.user_id = #{userId}")
    EditRow selectEditRow(@Param("userId") Long userId);

    /** 全量写（ADR-027 §3）：null=清空该列。 */
    @org.apache.ibatis.annotations.Update("UPDATE usr_customer_profile"
            + " SET default_contact_phone = #{contactPhone}, preferred_store_id = #{preferredStoreId},"
            + " marketing_opt_in = #{marketingOptIn}"
            + " WHERE user_id = #{userId} AND deleted = 0")
    int updateEditRow(@Param("userId") Long userId, @Param("contactPhone") String contactPhone,
                      @Param("preferredStoreId") Long preferredStoreId,
                      @Param("marketingOptIn") boolean marketingOptIn);

    /** 编辑投影行（含可空列，Map 自动映射需 @NoArgs 语义——沿用本接口内静态类）。 */
    class EditRow {
        private Long userId;
        private String defaultContactPhone;
        private Long preferredStoreId;
        private boolean marketingOptIn;

        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
        public String getDefaultContactPhone() { return defaultContactPhone; }
        public void setDefaultContactPhone(String v) { this.defaultContactPhone = v; }
        public Long getPreferredStoreId() { return preferredStoreId; }
        public void setPreferredStoreId(Long v) { this.preferredStoreId = v; }
        public boolean isMarketingOptIn() { return marketingOptIn; }
        public void setMarketingOptIn(boolean v) { this.marketingOptIn = v; }
    }
}
