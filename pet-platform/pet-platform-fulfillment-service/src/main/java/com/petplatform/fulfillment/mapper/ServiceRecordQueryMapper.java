package com.petplatform.fulfillment.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 履约记录只读投影（不建实体，纯读 ful_bath_record ∪ ful_walk_record，ADR-014）。
 */
@Mapper
public interface ServiceRecordQueryMapper {

    @Select("<script>"
            + "SELECT t.pet_id, t.appointment_id, t.type, t.finished_at FROM ("
            + "  SELECT x.*, ROW_NUMBER() OVER (PARTITION BY x.pet_id ORDER BY x.finished_at DESC) rn FROM ("
            + "    SELECT b.pet_id, b.appointment_id, 'BATH' AS type, b.finished_at"
            + "    FROM ful_bath_record b WHERE b.deleted = 0"
            + "    UNION ALL"
            + "    SELECT w.pet_id, w.appointment_id, 'WALK' AS type, w.finished_at"
            + "    FROM ful_walk_record w WHERE w.deleted = 0"
            + "  ) x WHERE x.pet_id IN"
            + "  <foreach collection=\"petIds\" item=\"pid\" open=\"(\" separator=\",\" close=\")\">#{pid}</foreach>"
            + ") t WHERE t.rn = 1"
            + "</script>")
    List<LastServiceRow> selectLastServices(@Param("petIds") Collection<Long> petIds);

    /**
     * 在门店预约集合内按宠物取最新履约：两个 IN 都在 SQL 下推，避免先取全库再在 Java 过滤。
     * 与 selectLastServices 使用同一 ROW_NUMBER 口径。
     */
    @Select("<script>"
            + "SELECT t.pet_id, t.appointment_id, t.type, t.finished_at FROM ("
            + "  SELECT x.*, ROW_NUMBER() OVER (PARTITION BY x.pet_id ORDER BY x.finished_at DESC) rn FROM ("
            + "    SELECT b.pet_id, b.appointment_id, 'BATH' AS type, b.finished_at"
            + "    FROM ful_bath_record b WHERE b.deleted = 0"
            + "      AND b.pet_id IN <foreach collection=\"petIds\" item=\"pid\" open=\"(\" separator=\",\" close=\")\">#{pid}</foreach>"
            + "      AND b.appointment_id IN <foreach collection=\"appointmentIds\" item=\"aid\" open=\"(\" separator=\",\" close=\")\">#{aid}</foreach>"
            + "    UNION ALL"
            + "    SELECT w.pet_id, w.appointment_id, 'WALK' AS type, w.finished_at"
            + "    FROM ful_walk_record w WHERE w.deleted = 0"
            + "      AND w.pet_id IN <foreach collection=\"petIds\" item=\"pid\" open=\"(\" separator=\",\" close=\")\">#{pid}</foreach>"
            + "      AND w.appointment_id IN <foreach collection=\"appointmentIds\" item=\"aid\" open=\"(\" separator=\",\" close=\")\">#{aid}</foreach>"
            + "  ) x"
            + ") t WHERE t.rn = 1"
            + "</script>")
    List<LastServiceRow> selectLastServicesInAppointments(@Param("petIds") Collection<Long> petIds,
                                                          @Param("appointmentIds") Collection<Long> appointmentIds);

    /**
     * 按预约批量取洗澡/美容记录（ADR-038 洗护看板：一屏最多几十单，一次 IN 查询，不做逐单 N+1）。
     * 洗澡与美容同表（{@code ful_bath_record}，V1:431 注释即"洗澡/美容服务记录"），区分靠服务码，所以这里不挑类型。
     * 不取最新一条而是直接查：表上有 {@code uk_appt(appointment_id)}，一预约最多一条记录；
     * 记录被软删（deleted≠0）时这里查不到 → 看板块显示「未提交」，与「还没做」同形是刻意的（库里确实没有活记录）。
     */
    @Select("<script>"
            + "SELECT b.appointment_id, b.employee_id, b.started_at, b.finished_at,"
            + "       b.service_items, b.remark,"
            + "       CASE WHEN JSON_TYPE(b.photos) = 'ARRAY' THEN JSON_LENGTH(b.photos) ELSE 0 END AS photo_count,"
            + "       b.by_manager"
            + "  FROM ful_bath_record b WHERE b.deleted = 0 AND b.appointment_id IN"
            + "  <foreach collection=\"appointmentIds\" item=\"aid\" open=\"(\" separator=\",\" close=\")\">#{aid}</foreach>"
            + "</script>")
    List<BathRecordRow> selectBathRecordsByAppointments(@Param("appointmentIds") Collection<Long> appointmentIds);

    /**
     * 按预约批量取遛狗记录（ADR-049 遛狗看板），口径与洗澡版一致：表上有 {@code uk_appt(appointment_id)}，
     * 一预约最多一条；软删（deleted≠0）即查不到 → 看板显示「未提交」，与「还没遛」同形是刻意的。
     * distance_m/track_summary/poop_count 仍刻意不取（全链路无写入入口，ADR-048 留 NULL）；
     * {@code photos} 自 2026-10-24 起有写入入口（完成后的补照片），这里取**原始 JSON 串**，
     * 由服务层受控解析成 key 列表——不在 SQL 里做 JSON 函数，避免脏值把整块看板打成 500。
     */
    @Select("<script>"
            + "SELECT w.appointment_id, w.employee_id, w.started_at, w.finished_at,"
            + "       w.duration_min, w.remark, w.by_manager, w.photos"
            + "  FROM ful_walk_record w WHERE w.deleted = 0 AND w.appointment_id IN"
            + "  <foreach collection=\"appointmentIds\" item=\"aid\" open=\"(\" separator=\",\" close=\")\">#{aid}</foreach>"
            + "</script>")
    List<WalkRecordRow> selectWalkRecordsByAppointments(@Param("appointmentIds") Collection<Long> appointmentIds);

    /** 记录投影行（service_items/photos 是 JSON 列的字符串形态，服务层受控解析）。 */
    class BathRecordRow {
        private Long appointmentId;
        private Long employeeId;
        private LocalDateTime startedAt;
        private LocalDateTime finishedAt;
        private String serviceItems;
        private String remark;
        private Integer photoCount;
        private Integer byManager;

        public Long getAppointmentId() { return appointmentId; }
        public void setAppointmentId(Long appointmentId) { this.appointmentId = appointmentId; }
        public Long getEmployeeId() { return employeeId; }
        public void setEmployeeId(Long employeeId) { this.employeeId = employeeId; }
        public LocalDateTime getStartedAt() { return startedAt; }
        public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
        public LocalDateTime getFinishedAt() { return finishedAt; }
        public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
        public String getServiceItems() { return serviceItems; }
        public void setServiceItems(String serviceItems) { this.serviceItems = serviceItems; }
        public String getRemark() { return remark; }
        public void setRemark(String remark) { this.remark = remark; }
        public Integer getPhotoCount() { return photoCount; }
        public void setPhotoCount(Integer photoCount) { this.photoCount = photoCount; }
        public Integer getByManager() { return byManager; }
        public void setByManager(Integer byManager) { this.byManager = byManager; }
    }

    /** 遛狗记录投影行（ADR-049；无 service_items/photos 列——遛狗表本就没有）。 */
    class WalkRecordRow {
        private Long appointmentId;
        private Long employeeId;
        private LocalDateTime startedAt;
        private LocalDateTime finishedAt;
        private Integer durationMin;
        private String remark;
        private Integer byManager;
        /** photos 原始 JSON 串（{"a","b"} / NULL），由服务层解析成 List<String> */
        private String photos;

        public Long getAppointmentId() { return appointmentId; }
        public void setAppointmentId(Long appointmentId) { this.appointmentId = appointmentId; }
        public Long getEmployeeId() { return employeeId; }
        public void setEmployeeId(Long employeeId) { this.employeeId = employeeId; }
        public LocalDateTime getStartedAt() { return startedAt; }
        public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
        public LocalDateTime getFinishedAt() { return finishedAt; }
        public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
        public Integer getDurationMin() { return durationMin; }
        public void setDurationMin(Integer durationMin) { this.durationMin = durationMin; }
        public String getRemark() { return remark; }
        public void setRemark(String remark) { this.remark = remark; }
        public Integer getByManager() { return byManager; }
        public void setByManager(Integer byManager) { this.byManager = byManager; }
        public String getPhotos() { return photos; }
        public void setPhotos(String photos) { this.photos = photos; }
    }

    /** 只读投影行。 */
    class LastServiceRow {
        private Long petId;
        private Long appointmentId;
        private String type;
        private LocalDateTime finishedAt;

        public Long getPetId() { return petId; }
        public void setPetId(Long petId) { this.petId = petId; }
        public Long getAppointmentId() { return appointmentId; }
        public void setAppointmentId(Long appointmentId) { this.appointmentId = appointmentId; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public LocalDateTime getFinishedAt() { return finishedAt; }
        public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    }
}
