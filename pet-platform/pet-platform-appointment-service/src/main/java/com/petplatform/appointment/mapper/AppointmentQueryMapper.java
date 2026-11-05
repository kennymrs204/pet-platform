package com.petplatform.appointment.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 预约只读投影（不建实体，纯读 apt_appointment）。
 */
@Mapper
public interface AppointmentQueryMapper {

    String COLUMNS = " a.id, a.appointment_no, a.service_date, a.start_time, a.end_time, a.status, "
            + "a.service_name_snapshot AS service_name, a.price_snapshot, a.source, a.employee_id, a.pet_id, "
            + "a.store_service_id ";

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM apt_appointment a
            WHERE a.deleted = 0 AND a.store_id = #{storeId} AND a.service_date = #{date}
            <if test="statuses != null and statuses.size() > 0">
              AND a.status IN <foreach collection="statuses" item="st" open="(" separator="," close=")">#{st}</foreach>
            </if>
            <if test="employeeId != null"> AND a.employee_id = #{employeeId}</if>
            </script>
            """)
    long countStore(@Param("storeId") Long storeId, @Param("date") LocalDate date,
                    @Param("statuses") List<String> statuses, @Param("employeeId") Long employeeId);

    @Select("<script>"
            + "SELECT " + COLUMNS
            + " FROM apt_appointment a"
            + " WHERE a.deleted = 0 AND a.store_id = #{storeId} AND a.service_date = #{date}"
            + "<if test=\"statuses != null and statuses.size() > 0\">"
            + " AND a.status IN <foreach collection=\"statuses\" item=\"st\" open=\"(\" separator=\",\" close=\")\">#{st}</foreach>"
            + "</if>"
            + "<if test=\"employeeId != null\"> AND a.employee_id = #{employeeId}</if>"
            + " ORDER BY (a.start_time IS NULL) ASC, a.start_time ASC, a.id ASC"
            + " LIMIT #{offset}, #{size}"
            + "</script>")
    List<AppointmentRow> selectStorePage(@Param("storeId") Long storeId, @Param("date") LocalDate date,
                                         @Param("statuses") List<String> statuses, @Param("employeeId") Long employeeId,
                                         @Param("offset") long offset, @Param("size") int size);

    @Select("SELECT DISTINCT a.pet_id FROM apt_appointment a WHERE a.deleted = 0 AND a.store_id = #{storeId}")
    java.util.Set<Long> selectPetIdsInStore(@Param("storeId") Long storeId);

    /**
     * 删除保护：全局按 pet_id 找一条未完成预约。只放行 COMPLETED/CANCELLED；
     * 未知/中间状态一律视为未完成（fail-closed）。LIMIT 1 让存在性判断尽早短路。
     */
    @Select("""
            SELECT EXISTS(
              SELECT 1 FROM apt_appointment a
               WHERE a.deleted = 0 AND a.pet_id = #{petId}
                 AND a.status NOT IN ('COMPLETED','CANCELLED')
               LIMIT 1)
            """)
    long existsBlockingByPet(@Param("petId") Long petId);

    /** 当前门店 + 目标宠物的预约 ID 集（最近履约摘要的门店过滤依据）。 */
    @Select("""
            <script>
            SELECT DISTINCT a.id FROM apt_appointment a
            WHERE a.deleted = 0 AND a.store_id = #{storeId}
              AND a.pet_id IN
              <foreach collection="petIds" item="pid" open="(" separator="," close=")">#{pid}</foreach>
            </script>
            """)
    java.util.Set<Long> selectAppointmentIdsInStore(@Param("storeId") Long storeId,
                                                    @Param("petIds") java.util.Collection<Long> petIds);

    /**
     * 本店客户聚合（ADR-017 §1）。valid_count 用于 hasValidAppointment（status ≠ CANCELLED 计数），
     * 不在 SQL 里剔除员工账号——预约域不感知员工域，由 app 装配层做差集。
     */
    @Select("SELECT a.user_id AS user_id, MIN(a.service_date) AS first_visit_date, MAX(a.service_date) AS last_visit_date,"
            + " COUNT(*) AS visit_count,"
            + " SUM(CASE WHEN a.status <> 'CANCELLED' THEN 1 ELSE 0 END) AS valid_count"
            + " FROM apt_appointment a WHERE a.deleted = 0 AND a.store_id = #{storeId} AND a.user_id IS NOT NULL"
            + " GROUP BY a.user_id")
    List<CustomerAggRow> selectCustomerAggInStore(@Param("storeId") Long storeId);

    /**
     * 某客户在本店的到店史（service_date desc→id desc；LIMIT/OFFSET 由服务层钳制后传入）。
     * OFFSET 分页的稳定性前提：本页翻页期间不新增该客户的预约（新增只出现在第 1 页之前，不挤动后续页序）。
     */
    @Select("SELECT a.id, a.service_date, a.service_name_snapshot AS service_name, a.status, a.price_snapshot,"
            + " a.employee_id, a.pet_id FROM apt_appointment a"
            + " WHERE a.deleted = 0 AND a.store_id = #{storeId} AND a.user_id = #{userId}"
            + " ORDER BY a.service_date DESC, a.id DESC LIMIT #{limit} OFFSET #{offset}")
    List<CustomerVisitRow> selectVisitsOfCustomer(@Param("storeId") Long storeId, @Param("userId") Long userId,
                                                  @Param("limit") int limit, @Param("offset") int offset);

    class CustomerAggRow {
        private Long userId;
        private LocalDate firstVisitDate;
        private LocalDate lastVisitDate;
        private long visitCount;
        private long validCount;

        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
        public LocalDate getFirstVisitDate() { return firstVisitDate; }
        public void setFirstVisitDate(LocalDate firstVisitDate) { this.firstVisitDate = firstVisitDate; }
        public LocalDate getLastVisitDate() { return lastVisitDate; }
        public void setLastVisitDate(LocalDate lastVisitDate) { this.lastVisitDate = lastVisitDate; }
        public long getVisitCount() { return visitCount; }
        public void setVisitCount(long visitCount) { this.visitCount = visitCount; }
        public long getValidCount() { return validCount; }
        public void setValidCount(long validCount) { this.validCount = validCount; }
    }

    class CustomerVisitRow {
        private Long id;
        private LocalDate serviceDate;
        private String serviceName;
        private String status;
        private Long priceSnapshot;
        private Long employeeId;
        private Long petId;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public LocalDate getServiceDate() { return serviceDate; }
        public void setServiceDate(LocalDate serviceDate) { this.serviceDate = serviceDate; }
        public String getServiceName() { return serviceName; }
        public void setServiceName(String serviceName) { this.serviceName = serviceName; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Long getPriceSnapshot() { return priceSnapshot; }
        public void setPriceSnapshot(Long priceSnapshot) { this.priceSnapshot = priceSnapshot; }
        public Long getEmployeeId() { return employeeId; }
        public void setEmployeeId(Long employeeId) { this.employeeId = employeeId; }
        public Long getPetId() { return petId; }
        public void setPetId(Long petId) { this.petId = petId; }
    }

    /** 托管快照（ADR-015）；不存在/已删返回 null。 */
    @Select("SELECT a.id, a.store_id, a.pet_id, a.status, a.boarding_checkin_date, a.boarding_checkout_date,"
            + " a.service_name_snapshot"
            + " FROM apt_appointment a WHERE a.deleted = 0 AND a.id = #{appointmentId}")
    BoardingSnapshotRow selectBoardingSnapshot(@Param("appointmentId") Long appointmentId);

    /** 用户的托管类（MULTI_DAY）预约 id 集（ADR-021 C 端住单列表归属推导）。 */
    @Select("SELECT a.id FROM apt_appointment a"
            + " WHERE a.deleted = 0 AND a.user_id = #{userId} AND a.boarding_checkin_date IS NOT NULL")
    List<Long> selectBoardingAppointmentIdsOfUser(@Param("userId") Long userId);

    /** 入住办理：CONFIRMED→SERVING（条件更新 + version 递增，§7.1）。 */
    @org.apache.ibatis.annotations.Update("UPDATE apt_appointment SET status = 'SERVING', version = version + 1,"
            + " actual_start_at = NOW(3)"
            + " WHERE id = #{appointmentId} AND deleted = 0 AND status = 'CONFIRMED'")
    int updateStartBoarding(@Param("appointmentId") Long appointmentId);

    /** 离店办理：SERVING→COMPLETED。 */
    @org.apache.ibatis.annotations.Update("UPDATE apt_appointment SET status = 'COMPLETED', version = version + 1,"
            + " actual_end_at = NOW(3)"
            + " WHERE id = #{appointmentId} AND deleted = 0 AND status = 'SERVING'")
    int updateCompleteBoarding(@Param("appointmentId") Long appointmentId);

    /** 托管快照投影行。 */
    class BoardingSnapshotRow {
        private Long id;
        private Long storeId;
        private Long petId;
        private String status;
        private java.time.LocalDate boardingCheckinDate;
        private java.time.LocalDate boardingCheckoutDate;
        private String serviceNameSnapshot;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getStoreId() { return storeId; }
        public void setStoreId(Long storeId) { this.storeId = storeId; }
        public Long getPetId() { return petId; }
        public void setPetId(Long petId) { this.petId = petId; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public java.time.LocalDate getBoardingCheckinDate() { return boardingCheckinDate; }
        public void setBoardingCheckinDate(java.time.LocalDate d) { this.boardingCheckinDate = d; }
        public java.time.LocalDate getBoardingCheckoutDate() { return boardingCheckoutDate; }
        public void setBoardingCheckoutDate(java.time.LocalDate d) { this.boardingCheckoutDate = d; }
        public String getServiceNameSnapshot() { return serviceNameSnapshot; }
        public void setServiceNameSnapshot(String s) { this.serviceNameSnapshot = s; }
    }

    @Select("SELECT DISTINCT a.pet_id FROM apt_appointment a"
            + " WHERE a.deleted = 0 AND a.store_id = #{storeId} AND a.employee_id = #{employeeId}")
    java.util.Set<Long> selectAssignedPetIds(@Param("storeId") Long storeId, @Param("employeeId") Long employeeId);

    /** 走 idx_emp_date(employee_id, service_date) 前缀命中（ADR-034 排班改/删预检），store/status 作后置过滤。 */
    @Select("SELECT COUNT(*) FROM apt_appointment a"
            + " WHERE a.deleted = 0 AND a.store_id = #{storeId} AND a.employee_id = #{employeeId}"
            + " AND a.service_date = #{workDate}"
            + " AND a.status IN ('PENDING_PAYMENT','CONFIRMED','CANCEL_REQUESTED','WAIT_SERVICE','SERVING')")
    long countScheduleOccupying(@Param("storeId") Long storeId, @Param("employeeId") Long employeeId,
                                @Param("workDate") java.time.LocalDate workDate);

    @Select("<script>"
            + "SELECT t.pet_id, t.appointment_id, t.service_name, t.service_date, t.status FROM ("
            + "  SELECT a.pet_id, a.id AS appointment_id, a.service_name_snapshot AS service_name,"
            + "         a.service_date, a.status,"
            + "         ROW_NUMBER() OVER (PARTITION BY a.pet_id ORDER BY a.service_date DESC, a.id DESC) rn"
            + "  FROM apt_appointment a"
            + "  WHERE a.deleted = 0 AND a.store_id = #{storeId} AND a.pet_id IN"
            + "  <foreach collection=\"petIds\" item=\"pid\" open=\"(\" separator=\",\" close=\")\">#{pid}</foreach>"
            + ") t WHERE t.rn = 1"
            + "</script>")
    List<LastAppointmentRow> selectLastAppointments(@Param("storeId") Long storeId,
                                                    @Param("petIds") java.util.Collection<Long> petIds);

    /**
     * 宠物服务历史计数。employee_id 非空时下推任务范围；count 与列表共用同一 WHERE，
     * 避免 total 与页码漂移。
     */
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM apt_appointment a
            WHERE a.deleted = 0 AND a.store_id = #{storeId} AND a.pet_id = #{petId}
            <if test="employeeId != null"> AND a.employee_id = #{employeeId}</if>
            </script>
            """)
    long countPetServiceHistory(@Param("storeId") Long storeId, @Param("petId") Long petId,
                                @Param("employeeId") Long employeeId);

    /** 宠物服务历史分页；store_id + pet_id + 可选 employee_id 全部下推 SQL。 */
    @Select("""
            <script>
            SELECT a.id, a.appointment_no, a.pet_id, a.service_date, a.start_time, a.end_time,
                   a.service_name_snapshot AS service_name, a.status, a.price_snapshot, a.employee_id,
                   a.boarding_checkin_date, a.boarding_checkout_date, a.actual_end_at
            FROM apt_appointment a
            WHERE a.deleted = 0 AND a.store_id = #{storeId} AND a.pet_id = #{petId}
            <if test="employeeId != null"> AND a.employee_id = #{employeeId}</if>
            ORDER BY a.service_date DESC, a.id DESC
            LIMIT #{offset}, #{size}
            </script>
            """)
    List<PetServiceVisitRow> selectPetServiceHistory(@Param("storeId") Long storeId, @Param("petId") Long petId,
                                                     @Param("employeeId") Long employeeId,
                                                     @Param("offset") long offset, @Param("size") int size);

    /** 宠物服务历史投影行（预约模块只读，不建实体）。 */
    class PetServiceVisitRow {
        private Long id;
        private String appointmentNo;
        private Long petId;
        private LocalDate serviceDate;
        private LocalTime startTime;
        private LocalTime endTime;
        private String serviceName;
        private String status;
        private Long priceSnapshot;
        private Long employeeId;
        private LocalDate boardingCheckinDate;
        private LocalDate boardingCheckoutDate;
        private java.time.LocalDateTime actualEndAt;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getAppointmentNo() { return appointmentNo; }
        public void setAppointmentNo(String appointmentNo) { this.appointmentNo = appointmentNo; }
        public Long getPetId() { return petId; }
        public void setPetId(Long petId) { this.petId = petId; }
        public LocalDate getServiceDate() { return serviceDate; }
        public void setServiceDate(LocalDate serviceDate) { this.serviceDate = serviceDate; }
        public LocalTime getStartTime() { return startTime; }
        public void setStartTime(LocalTime startTime) { this.startTime = startTime; }
        public LocalTime getEndTime() { return endTime; }
        public void setEndTime(LocalTime endTime) { this.endTime = endTime; }
        public String getServiceName() { return serviceName; }
        public void setServiceName(String serviceName) { this.serviceName = serviceName; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Long getPriceSnapshot() { return priceSnapshot; }
        public void setPriceSnapshot(Long priceSnapshot) { this.priceSnapshot = priceSnapshot; }
        public Long getEmployeeId() { return employeeId; }
        public void setEmployeeId(Long employeeId) { this.employeeId = employeeId; }
        public LocalDate getBoardingCheckinDate() { return boardingCheckinDate; }
        public void setBoardingCheckinDate(LocalDate boardingCheckinDate) { this.boardingCheckinDate = boardingCheckinDate; }
        public LocalDate getBoardingCheckoutDate() { return boardingCheckoutDate; }
        public void setBoardingCheckoutDate(LocalDate boardingCheckoutDate) { this.boardingCheckoutDate = boardingCheckoutDate; }
        public java.time.LocalDateTime getActualEndAt() { return actualEndAt; }
        public void setActualEndAt(java.time.LocalDateTime actualEndAt) { this.actualEndAt = actualEndAt; }
    }

    /** 最近预约投影行。 */
    class LastAppointmentRow {
        private Long petId;
        private Long appointmentId;
        private String serviceName;
        private java.time.LocalDate serviceDate;
        private String status;

        public Long getPetId() { return petId; }
        public void setPetId(Long petId) { this.petId = petId; }
        public Long getAppointmentId() { return appointmentId; }
        public void setAppointmentId(Long appointmentId) { this.appointmentId = appointmentId; }
        public String getServiceName() { return serviceName; }
        public void setServiceName(String serviceName) { this.serviceName = serviceName; }
        public java.time.LocalDate getServiceDate() { return serviceDate; }
        public void setServiceDate(java.time.LocalDate serviceDate) { this.serviceDate = serviceDate; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }

    /** 只读投影行（map-underscore-to-camel 自动映射；service_name_snapshot 手工别名映射）。 */
    class AppointmentRow {
        private Long id;
        private String appointmentNo;
        private LocalDate serviceDate;
        private LocalTime startTime;
        private LocalTime endTime;
        private String status;
        private String serviceName;
        private Long priceSnapshot;
        private String source;
        private Long employeeId;
        private Long petId;
        /** 上架服务 id（ADR-038 洗护看板据此取服务码/所需台位类型；服务名快照不能当判据） */
        private Long storeServiceId;

        public Long getStoreServiceId() { return storeServiceId; }
        public void setStoreServiceId(Long storeServiceId) { this.storeServiceId = storeServiceId; }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getAppointmentNo() { return appointmentNo; }
        public void setAppointmentNo(String appointmentNo) { this.appointmentNo = appointmentNo; }
        public LocalDate getServiceDate() { return serviceDate; }
        public void setServiceDate(LocalDate serviceDate) { this.serviceDate = serviceDate; }
        public LocalTime getStartTime() { return startTime; }
        public void setStartTime(LocalTime startTime) { this.startTime = startTime; }
        public LocalTime getEndTime() { return endTime; }
        public void setEndTime(LocalTime endTime) { this.endTime = endTime; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getServiceName() { return serviceName; }
        public void setServiceName(String serviceName) { this.serviceName = serviceName; }
        public Long getPriceSnapshot() { return priceSnapshot; }
        public void setPriceSnapshot(Long priceSnapshot) { this.priceSnapshot = priceSnapshot; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public Long getEmployeeId() { return employeeId; }
        public void setEmployeeId(Long employeeId) { this.employeeId = employeeId; }
        public Long getPetId() { return petId; }
        public void setPetId(Long petId) { this.petId = petId; }
    }
}
