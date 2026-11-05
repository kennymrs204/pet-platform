package com.petplatform.catalog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.petplatform.catalog.domain.StoreServiceEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StoreServiceMapper extends BaseMapper<StoreServiceEntity> {

    /** 公开浏览简报（ADR-022）：双 ON、双未删；时长=门店覆盖优先回落平台默认（SQL 端 COALESCE）。 */
    @org.apache.ibatis.annotations.Select("<script>"
            + "SELECT css.store_id AS storeId, css.id AS storeServiceId, cs.name AS name,"
            + " cs.category AS category, css.price AS price,"
            + " COALESCE(css.duration_min, cs.required_duration_min) AS durationMin"
            + " FROM cat_store_service css JOIN cat_service cs ON cs.id = css.service_id"
            + " AND cs.deleted = 0 AND cs.status = 'ON'"
            + " WHERE css.deleted = 0 AND css.status = 'ON' AND css.store_id IN"
            + " <foreach collection=\"storeIds\" item=\"sid\" open=\"(\" separator=\",\" close=\")\">#{sid}</foreach>"
            + " ORDER BY css.store_id ASC, css.id ASC"
            + "</script>")
    java.util.List<BriefRow> selectActiveBriefsByStores(@org.apache.ibatis.annotations.Param("storeIds")
                                                                java.util.Collection<Long> storeIds);

    /** 简报投影行（列别名直映射，纯读不入实体）。 */
    class BriefRow {
        private Long storeId;
        private Long storeServiceId;
        private String name;
        private String category;
        private Long price;
        private Integer durationMin;

        public Long getStoreId() { return storeId; }
        public void setStoreId(Long storeId) { this.storeId = storeId; }
        public Long getStoreServiceId() { return storeServiceId; }
        public void setStoreServiceId(Long storeServiceId) { this.storeServiceId = storeServiceId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public Long getPrice() { return price; }
        public void setPrice(Long price) { this.price = price; }
        public Integer getDurationMin() { return durationMin; }
        public void setDurationMin(Integer durationMin) { this.durationMin = durationMin; }

        public com.petplatform.catalog.api.dto.StoreServiceBrief toBrief() {
            return com.petplatform.catalog.api.dto.StoreServiceBrief.builder()
                    .storeId(storeId).storeServiceId(storeServiceId).name(name)
                    .category(category).price(price).durationMin(durationMin).build();
        }
    }
}
