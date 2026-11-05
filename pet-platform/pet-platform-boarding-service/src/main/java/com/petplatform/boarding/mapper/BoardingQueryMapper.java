package com.petplatform.boarding.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 托管只读投影（不建实体，纯读 boa_boarding_stay）。
 */
@Mapper
public interface BoardingQueryMapper {

    @Select("""
            <script>
            SELECT COUNT(*) FROM boa_boarding_stay b
            WHERE b.deleted = 0 AND b.store_id = #{storeId}
            <if test="statuses != null and statuses.size() > 0">
              AND b.status IN <foreach collection="statuses" item="st" open="(" separator="," close=")">#{st}</foreach>
            </if>
            </script>
            """)
    long countByStatuses(@Param("storeId") Long storeId, @Param("statuses") Collection<String> statuses);

    @Select("""
            <script>
            SELECT b.id, b.appointment_id, b.pet_id, b.checkin_date, b.checkout_date,
                   b.actual_checkin_at, b.status, b.caretaker_id
            FROM boa_boarding_stay b
            WHERE b.deleted = 0 AND b.store_id = #{storeId}
            <if test="statuses != null and statuses.size() > 0">
              AND b.status IN <foreach collection="statuses" item="st" open="(" separator="," close=")">#{st}</foreach>
            </if>
            <if test="caretakerId != null"> AND b.caretaker_id = #{caretakerId} </if>
            ORDER BY b.checkin_date ASC, b.id ASC
            LIMIT #{limit}
            </script>
            """)
    List<StayRow> selectStays(@Param("storeId") Long storeId, @Param("statuses") Collection<String> statuses,
                              @Param("caretakerId") Long caretakerId, @Param("limit") int limit);

    /**
     * 历史住单分页（ADR-032 片2）。三处刻意与 {@link #selectStays} 不同，各自只有一个真相：
     * 排序按 {@code checkout_date DESC}（浏览历史=先看最近的），日期区间打在 {@code checkout_date}
     * （与排序同列，否则"按离店日排序、按入住日筛选"会让人看不懂自己看到的窗口），
     * 以及 count 与列表**共用同一段 WHERE**（两条 SQL 的过滤条件漂移过一次就是 total 与页码对不上）。
     */
    @Select("""
            <script>
            SELECT b.id, b.appointment_id, b.pet_id, b.checkin_date, b.checkout_date,
                   b.actual_checkin_at, b.status, b.caretaker_id
            FROM boa_boarding_stay b
            WHERE b.deleted = 0 AND b.store_id = #{storeId}
            AND b.status IN <foreach collection="statuses" item="st" open="(" separator="," close=")">#{st}</foreach>
            <if test="caretakerId != null"> AND b.caretaker_id = #{caretakerId} </if>
            <if test="dateFrom != null"> AND b.checkout_date &gt;= #{dateFrom} </if>
            <if test="dateTo != null"> AND b.checkout_date &lt;= #{dateTo} </if>
            ORDER BY b.checkout_date DESC, b.id DESC
            LIMIT #{offset}, #{size}
            </script>
            """)
    List<StayRow> selectHistoryPage(@Param("storeId") Long storeId, @Param("statuses") Collection<String> statuses,
                                    @Param("caretakerId") Long caretakerId, @Param("dateFrom") LocalDate dateFrom,
                                    @Param("dateTo") LocalDate dateTo, @Param("offset") int offset,
                                    @Param("size") int size);

    @Select("""
            <script>
            SELECT COUNT(*) FROM boa_boarding_stay b
            WHERE b.deleted = 0 AND b.store_id = #{storeId}
            AND b.status IN <foreach collection="statuses" item="st" open="(" separator="," close=")">#{st}</foreach>
            <if test="caretakerId != null"> AND b.caretaker_id = #{caretakerId} </if>
            <if test="dateFrom != null"> AND b.checkout_date &gt;= #{dateFrom} </if>
            <if test="dateTo != null"> AND b.checkout_date &lt;= #{dateTo} </if>
            </script>
            """)
    long countHistory(@Param("storeId") Long storeId, @Param("statuses") Collection<String> statuses,
                      @Param("caretakerId") Long caretakerId, @Param("dateFrom") LocalDate dateFrom,
                      @Param("dateTo") LocalDate dateTo);

    @Select("<script>"
            + "SELECT DISTINCT b.pet_id FROM boa_boarding_stay b"
            + " WHERE b.deleted = 0 AND b.store_id = #{storeId} AND b.status IN"
            + " <foreach collection=\"statuses\" item=\"st\" open=\"(\" separator=\",\" close=\")\">#{st}</foreach>"
            + "</script>")
    java.util.Set<Long> selectInStorePetIds(@Param("storeId") Long storeId,
                                            @Param("statuses") Collection<String> statuses);

    @Select("<script>"
            + "SELECT DISTINCT b.pet_id FROM boa_boarding_stay b"
            + " WHERE b.deleted = 0 AND b.store_id = #{storeId} AND b.caretaker_id = #{caretakerId} AND b.status IN"
            + " <foreach collection=\"statuses\" item=\"st\" open=\"(\" separator=\",\" close=\")\">#{st}</foreach>"
            + "</script>")
    java.util.Set<Long> selectCaretakerPetIds(@Param("storeId") Long storeId, @Param("caretakerId") Long caretakerId,
                                              @Param("statuses") Collection<String> statuses);

    @Select("<script>"
            + "SELECT b.id, b.appointment_id, b.pet_id, b.checkin_date, b.checkout_date,"
            + "       b.actual_checkin_at, b.status, b.caretaker_id"
            + " FROM boa_boarding_stay b"
            + " WHERE b.deleted = 0 AND b.status IN"
            + " <foreach collection=\"statuses\" item=\"st\" open=\"(\" separator=\",\" close=\")\">#{st}</foreach>"
            + " AND b.pet_id IN <foreach collection=\"petIds\" item=\"pid\" open=\"(\" separator=\",\" close=\")\">#{pid}</foreach>"
            + " ORDER BY b.checkin_date ASC, b.id ASC LIMIT 100"
            + "</script>")
    List<StayRow> selectCurrentStays(@Param("petIds") Collection<Long> petIds,
                                     @Param("statuses") Collection<String> statuses);

    /** 删除保护：全局按 pet_id 找一条未完成住单；只有 CHECKED_OUT 放行，未知状态 fail-closed。 */
    @Select("""
            SELECT EXISTS(
              SELECT 1 FROM boa_boarding_stay b
               WHERE b.deleted = 0 AND b.pet_id = #{petId}
                 AND b.status <> 'CHECKED_OUT'
               LIMIT 1)
            """)
    long existsBlockingByPet(@Param("petId") Long petId);

    /** 按当前页预约批量取住单（所有状态；storeId 再兜底一次）。 */
    @Select("<script>"
            + "SELECT b.id, b.appointment_id, b.pet_id, b.checkin_date, b.checkout_date,"
            + "       b.actual_checkin_at, b.status, b.caretaker_id"
            + " FROM boa_boarding_stay b"
            + " WHERE b.deleted = 0 AND b.store_id = #{storeId} AND b.appointment_id IN"
            + " <foreach collection=\"appointmentIds\" item=\"aid\" open=\"(\" separator=\",\" close=\")\">#{aid}</foreach>"
            + " ORDER BY b.checkin_date ASC, b.id ASC LIMIT 100"
            + "</script>")
    List<StayRow> selectStaysByAppointments(@Param("storeId") Long storeId,
                                            @Param("appointmentIds") Collection<Long> appointmentIds);

    /**
     * 主列表分页与状态分组**共用**的 WHERE 片段（同一条 SQL 片段被三处 include，
     * 过滤条件不可能各写一份再漂移；分页/计数对不上就是这类漂移的症状）。
     *
     * <p>关键词维度是 id 集合（宠物名/客户昵称/手机号由所属模块**完整**解析），且始终与
     * {@code store_id} / 状态集合 / 护理员范围 **AND** 在一起——命中别店的同名宠物不会浮现。
     *
     * <p>{@code CLAUSE_*} 决定形状：候选超片时一条逻辑查询会被切成多片，
     * 每片各自只匹配"属于自己的那部分行"（数值片与宠物片之间用 {@code NOT (id=… OR appointment_id=…)}
     * 互斥），因此分片计数相加=完整匹配数、分片行归并=完整有序结果，不会重也不会漏。
     *
     * <p>这份互斥成立的前提是 {@code appointment_id}（与 {@code pet_id}）在 DDL 里 {@code NOT NULL}：
     * 若可为 NULL，{@code NOT (id=n OR appointment_id=n)} 在三值逻辑下会把 {@code appointment_id}
     * 为 NULL 的行整体判成 NULL 而**静默丢掉**——正是分片要防的那类漏数据。改列可空性前先改这里。
     */
    String STAY_FILTER = """
            <if test="q.statuses != null and q.statuses.size() > 0">
              AND b.status IN <foreach collection="q.statuses" item="st" open="(" separator="," close=")">#{st}</foreach>
            </if>
            <if test="q.caretakerId != null"> AND b.caretaker_id = #{q.caretakerId} </if>
            <if test="q.keyword">
              <choose>
                <when test="q.keywordImpossible"> AND 1 = 0 </when>
                <when test="q.keywordClause == 1">
                  AND (b.id = #{q.keywordNumeric} OR b.appointment_id = #{q.keywordNumeric})
                </when>
                <when test="q.keywordClause == 2">
                  AND (b.pet_id IN <foreach collection="q.keywordPetIds" item="pid" open="(" separator="," close=")">#{pid}</foreach>
                       AND NOT (b.id = #{q.keywordNumeric} OR b.appointment_id = #{q.keywordNumeric}))
                </when>
                <when test="q.keywordClause == 3">
                  AND b.pet_id IN <foreach collection="q.keywordPetIds" item="pid" open="(" separator="," close=")">#{pid}</foreach>
                </when>
                <otherwise>
                  AND (
                  <trim prefixOverrides="OR">
                    <if test="q.keywordNumeric != null"> OR b.id = #{q.keywordNumeric} OR b.appointment_id = #{q.keywordNumeric} </if>
                    <if test="q.keywordPetIds != null and q.keywordPetIds.size() > 0">
                      OR b.pet_id IN <foreach collection="q.keywordPetIds" item="pid" open="(" separator="," close=")">#{pid}</foreach>
                    </if>
                  </trim>
                  )
                </otherwise>
              </choose>
            </if>
            """;

    /**
     * 主列表服务端分页。排序沿用既有 {@code checkin_date ASC, id ASC}——
     * id 作为次级键让同一入住日内的顺序确定，翻页不会重复或漏记录。
     */
    @Select("""
            <script>
            SELECT b.id, b.appointment_id, b.pet_id, b.checkin_date, b.checkout_date,
                   b.actual_checkin_at, b.status, b.caretaker_id
            FROM boa_boarding_stay b
            WHERE b.deleted = 0 AND b.store_id = #{q.storeId}
            """ + STAY_FILTER + """
            ORDER BY b.checkin_date ASC, b.id ASC
            LIMIT #{offset}, #{size}
            </script>
            """)
    List<StayRow> selectStayPage(@Param("q") com.petplatform.boarding.api.dto.BoardingStayPageQuery q,
                                 @Param("offset") int offset, @Param("size") int size);

    /** 与 {@link #selectStayPage} 同 WHERE 的准确总数（同一 query 实例，不重传参数）。 */
    @Select("""
            <script>
            SELECT COUNT(*) FROM boa_boarding_stay b
            WHERE b.deleted = 0 AND b.store_id = #{q.storeId}
            """ + STAY_FILTER + """
            </script>
            """)
    long countStays(@Param("q") com.petplatform.boarding.api.dto.BoardingStayPageQuery q);

    /** 一次 GROUP BY 出全部状态计数（页签角标用；不为每个页签各拉一次全量）。 */
    @Select("""
            <script>
            SELECT b.status AS status, COUNT(*) AS total
            FROM boa_boarding_stay b
            WHERE b.deleted = 0 AND b.store_id = #{q.storeId}
            """ + STAY_FILTER + """
            GROUP BY b.status
            </script>
            """)
    List<StatusCount> countStaysByStatus(@Param("q") com.petplatform.boarding.api.dto.BoardingStayPageQuery q);

    /** 状态分组投影。 */
    class StatusCount {
        private String status;
        private long total;

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public long getTotal() { return total; }
        public void setTotal(long total) { this.total = total; }
    }

    /** 只读投影行（map-underscore-to-camel 自动映射）。 */
    class StayRow {
        private Long id;
        private Long appointmentId;
        private Long petId;
        private LocalDate checkinDate;
        private LocalDate checkoutDate;
        private LocalDateTime actualCheckinAt;
        private String status;
        private Long caretakerId;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getAppointmentId() { return appointmentId; }
        public void setAppointmentId(Long appointmentId) { this.appointmentId = appointmentId; }
        public Long getPetId() { return petId; }
        public void setPetId(Long petId) { this.petId = petId; }
        public LocalDate getCheckinDate() { return checkinDate; }
        public void setCheckinDate(LocalDate checkinDate) { this.checkinDate = checkinDate; }
        public LocalDate getCheckoutDate() { return checkoutDate; }
        public void setCheckoutDate(LocalDate checkoutDate) { this.checkoutDate = checkoutDate; }
        public LocalDateTime getActualCheckinAt() { return actualCheckinAt; }
        public void setActualCheckinAt(LocalDateTime actualCheckinAt) { this.actualCheckinAt = actualCheckinAt; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Long getCaretakerId() { return caretakerId; }
        public void setCaretakerId(Long caretakerId) { this.caretakerId = caretakerId; }
    }
}
