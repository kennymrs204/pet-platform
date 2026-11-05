package com.petplatform.store.mapper;

import com.petplatform.store.api.dto.StoreView;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 门店只读投影（不建实体，纯读 sto_store，表 Owner=store）。
 */
@Mapper
public interface StoreQueryMapper {

    @Select("SELECT s.id, s.name, s.status, s.store_type, s.business_start_time, s.business_end_time,"
            + " s.break_start_time, s.break_end_time"
            + " FROM sto_store s WHERE s.deleted = 0 AND s.id = #{storeId}")
    StoreViewRow selectStore(@Param("storeId") Long storeId);

    /** 公开浏览（ADR-022）：OPEN ∧ 非总店 ∧ 未删；keyword LIKE 名称/地址；id 升序。 */
    @Select("<script>"
            + "SELECT s.id, s.name, s.address, s.phone, s.status FROM sto_store s"
            + " WHERE s.deleted = 0 AND s.status = 'OPEN' AND s.store_type &lt;&gt; 'PLATFORM_HQ'"
            + " <if test=\"keyword != null and keyword != ''\">AND (s.name LIKE CONCAT('%',#{keyword},'%')"
            + " OR s.address LIKE CONCAT('%',#{keyword},'%'))</if>"
            + " ORDER BY s.id ASC LIMIT 200"
            + "</script>")
    java.util.List<PublicStoreRow> selectPublicStores(@Param("keyword") String keyword);

    @Select("SELECT s.id, s.name, s.address, s.phone, s.status FROM sto_store s"
            + " WHERE s.deleted = 0 AND s.status = 'OPEN' AND s.store_type <> 'PLATFORM_HQ' AND s.id = #{storeId}")
    PublicStoreRow selectPublicStore(@Param("storeId") Long storeId);

    @Select("<script>SELECT s.id, s.name FROM sto_store s WHERE s.deleted = 0 AND s.id IN"
            + " <foreach collection=\"storeIds\" item=\"sid\" open=\"(\" separator=\",\" close=\")\">#{sid}</foreach>"
            + "</script>")
    java.util.List<PublicStoreRow> selectNamesByIds(@Param("storeIds") java.util.Collection<Long> storeIds);

    /** 公开浏览投影行。 */
    class PublicStoreRow {
        private Long id;
        private String name;
        private String address;
        private String phone;
        private String status;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public com.petplatform.store.api.dto.PublicStoreView toView() {
            return com.petplatform.store.api.dto.PublicStoreView.builder()
                    .id(id).name(name).address(address).phone(phone).status(status).build();
        }
    }

    /** 只读投影行（map-underscore-to-camel 需列名对齐，故显式别名类）。 */
    class StoreViewRow {
        private Long id;
        private String name;
        private String status;
        private String storeType;
        private java.time.LocalTime businessStartTime;
        private java.time.LocalTime businessEndTime;
        private java.time.LocalTime breakStartTime;
        private java.time.LocalTime breakEndTime;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getStoreType() { return storeType; }
        public void setStoreType(String storeType) { this.storeType = storeType; }
        public java.time.LocalTime getBusinessStartTime() { return businessStartTime; }
        public void setBusinessStartTime(java.time.LocalTime t) { this.businessStartTime = t; }
        public java.time.LocalTime getBusinessEndTime() { return businessEndTime; }
        public void setBusinessEndTime(java.time.LocalTime t) { this.businessEndTime = t; }
        public java.time.LocalTime getBreakStartTime() { return breakStartTime; }
        public void setBreakStartTime(java.time.LocalTime t) { this.breakStartTime = t; }
        public java.time.LocalTime getBreakEndTime() { return breakEndTime; }
        public void setBreakEndTime(java.time.LocalTime t) { this.breakEndTime = t; }

        public StoreView toView() {
            return StoreView.builder().id(id).name(name).status(status).storeType(storeType)
                    .businessStart(businessStartTime).businessEnd(businessEndTime)
                    .breakStart(breakStartTime).breakEnd(breakEndTime)
                    .build();
        }
    }
}
