package com.petplatform.schedule.mapper;

import com.petplatform.schedule.api.dto.KennelCapacityView;
import com.petplatform.schedule.api.dto.KennelResourceView;
import com.petplatform.schedule.api.dto.OccupancyView;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * 笼位资源/占用读写（sch_resource / sch_resource_schedule，表 Owner=schedule，ADR-015）。
 * sch_resource_schedule 为 P2 表（无 deleted 列，释放=物理删行），不走 BaseEntity。
 */
@Mapper
public interface ResourceOccupancyMapper {

    @Select("SELECT r.id, r.store_id, r.type, r.name, r.status FROM sch_resource r"
            + " WHERE r.deleted = 0 AND r.id = #{resourceId} AND r.store_id = #{storeId}")
    KennelResourceView selectResource(@Param("storeId") Long storeId, @Param("resourceId") Long resourceId);

    /** 资源行锁定读：新占用写入与停用检查共用同一条资源行作为互斥点。 */
    @Select("SELECT r.id, r.store_id, r.type, r.name, r.status FROM sch_resource r"
            + " WHERE r.deleted = 0 AND r.id = #{resourceId} AND r.store_id = #{storeId} FOR UPDATE")
    KennelResourceView selectResourceForUpdate(@Param("storeId") Long storeId,
                                               @Param("resourceId") Long resourceId);

    /**
     * 未来/当日占用存在性锁定读。LIMIT 1 只判存在；FOR UPDATE 读最新已提交行，
     * 避免 REPEATABLE READ 的旧快照在等待资源行锁后漏看刚提交的占用。
     */
    @Select("SELECT o.id FROM sch_resource_schedule o"
            + " WHERE o.resource_id = #{resourceId} AND o.work_date >= #{fromDate}"
            + " LIMIT 1 FOR UPDATE")
    Long selectReservationIdFromForUpdate(@Param("resourceId") Long resourceId,
                                          @Param("fromDate") LocalDate fromDate);

    @Select("""
            <script>
            SELECT r.type AS type, COUNT(*) AS total
            FROM sch_resource r
            WHERE r.deleted = 0 AND r.store_id = #{storeId} AND r.status = 'AVAILABLE'
              AND r.type IN ('KENNEL_SMALL','KENNEL_MEDIUM','KENNEL_LARGE','CAT_ROOM')
            <if test="type != null and type != ''"> AND r.type = #{type}</if>
            GROUP BY r.type
            </script>
            """)
    List<KennelCapacityView> selectKennelTotals(@Param("storeId") Long storeId, @Param("type") String type);

    @Select("""
            <script>
            SELECT r.type AS type, COUNT(DISTINCT o.resource_id) AS occupied
            FROM sch_resource_schedule o
            JOIN sch_resource r ON r.id = o.resource_id AND r.deleted = 0
            WHERE o.work_date = #{date} AND o.occupy_type = 'DAY'
              AND r.store_id = #{storeId}
              AND r.type IN ('KENNEL_SMALL','KENNEL_MEDIUM','KENNEL_LARGE','CAT_ROOM')
            <if test="type != null and type != ''"> AND r.type = #{type}</if>
            GROUP BY r.type
            </script>
            """)
    List<KennelCapacityView> selectKennelOccupied(@Param("storeId") Long storeId, @Param("date") LocalDate date,
                                                  @Param("type") String type);

    @Select("SELECT o.resource_id, o.work_date FROM sch_resource_schedule o"
            + " WHERE o.appointment_id = #{appointmentId} AND o.occupy_type = 'DAY'")
    List<OccupancyView> selectOccupancy(@Param("appointmentId") Long appointmentId);

    /** 本预约某日 TIME_SLOT 占用（改期候选选择时只排除自己的旧占用）。 */
    @Select("SELECT o.resource_id AS resourceId, o.slot_no AS slotNo FROM sch_resource_schedule o"
            + " WHERE o.appointment_id = #{appointmentId} AND o.work_date = #{date}"
            + " AND o.occupy_type = 'TIME_SLOT'")
    List<SlotOccupyRow> selectTimeSlotOccupancyOfAppointment(@Param("appointmentId") Long appointmentId,
                                                             @Param("date") LocalDate date);

    /** 目标资源/日期/时段内其他预约的 TIME_SLOT 占用存在性锁定读。 */
    @Select("SELECT o.id FROM sch_resource_schedule o"
            + " WHERE o.resource_id = #{resourceId} AND o.work_date = #{date}"
            + " AND o.occupy_type = 'TIME_SLOT'"
            + " AND o.slot_no >= #{fromSlot} AND o.slot_no < #{toSlotExclusive}"
            + " AND o.appointment_id <> #{excludeAppointmentId}"
            + " LIMIT 1 FOR UPDATE")
    Long selectOtherTimeSlotOccupancyForUpdate(@Param("resourceId") Long resourceId,
                                               @Param("date") LocalDate date,
                                               @Param("fromSlot") int fromSlot,
                                               @Param("toSlotExclusive") int toSlotExclusive,
                                               @Param("excludeAppointmentId") Long excludeAppointmentId);

    /** 目标资源 [from,toExclusive) 内其他预约的 DAY 占用存在性锁定读。 */
    @Select("SELECT o.id FROM sch_resource_schedule o"
            + " WHERE o.resource_id = #{resourceId} AND o.occupy_type = 'DAY'"
            + " AND o.work_date >= #{from} AND o.work_date < #{toExclusive}"
            + " AND o.appointment_id <> #{excludeAppointmentId}"
            + " LIMIT 1 FOR UPDATE")
    Long selectOtherDayOccupancyForUpdate(@Param("resourceId") Long resourceId,
                                          @Param("from") LocalDate from,
                                          @Param("toExclusive") LocalDate toExclusive,
                                          @Param("excludeAppointmentId") Long excludeAppointmentId);

    @Delete("DELETE FROM sch_resource_schedule WHERE appointment_id = #{appointmentId} AND occupy_type = 'DAY'")
    int deleteOccupancyOfAppointment(@Param("appointmentId") Long appointmentId);

    @Delete("""
            <script>
            DELETE FROM sch_resource_schedule
            WHERE appointment_id = #{appointmentId} AND occupy_type = 'DAY'
              AND work_date &gt;= #{from} AND work_date &lt; #{toExclusive}
            </script>
            """)
    int deleteOccupancyInRange(@Param("appointmentId") Long appointmentId,
                               @Param("from") LocalDate from, @Param("toExclusive") LocalDate toExclusive);

    @Insert("INSERT INTO sch_resource_schedule (id, resource_id, work_date, slot_no, appointment_id, occupy_type)"
            + " VALUES (#{id}, #{resourceId}, #{workDate}, 99, #{appointmentId}, 'DAY')")
    int insertDayOccupancy(@Param("id") Long id, @Param("resourceId") Long resourceId,
                           @Param("workDate") LocalDate workDate, @Param("appointmentId") Long appointmentId);

    @Insert("INSERT INTO sch_resource_schedule (id, resource_id, work_date, slot_no, appointment_id, occupy_type)"
            + " VALUES (#{id}, #{resourceId}, #{workDate}, #{slotNo}, #{appointmentId}, 'TIME_SLOT')")
    int insertSlotOccupancy(@Param("id") Long id, @Param("resourceId") Long resourceId,
                            @Param("workDate") LocalDate workDate, @Param("slotNo") int slotNo,
                            @Param("appointmentId") Long appointmentId);

    @Delete("DELETE FROM sch_resource_schedule WHERE appointment_id = #{appointmentId}")
    int deleteAllOfAppointment(@Param("appointmentId") Long appointmentId);

    @Select("SELECT r.id, r.store_id, r.type, r.name, r.status FROM sch_resource r"
            + " WHERE r.deleted = 0 AND r.store_id = #{storeId} AND r.status = 'AVAILABLE'"
            + " AND r.type = #{type} ORDER BY r.id ASC")
    java.util.List<com.petplatform.schedule.api.dto.KennelResourceView> selectAvailableResources(
            @Param("storeId") Long storeId, @Param("type") String type);

    @Select("SELECT o.resource_id AS resourceId, o.slot_no AS slotNo FROM sch_resource_schedule o"
            + " JOIN sch_resource r ON r.id = o.resource_id AND r.deleted = 0"
            + " WHERE r.store_id = #{storeId} AND r.type = #{type} AND o.work_date = #{date}"
            + " AND o.occupy_type = 'TIME_SLOT'")
    java.util.List<SlotOccupyRow> selectDaySlotOccupancy(@Param("storeId") Long storeId,
                                                         @Param("type") String type,
                                                         @Param("date") LocalDate date);

    @Select("SELECT DISTINCT o.resource_id FROM sch_resource_schedule o"
            + " JOIN sch_resource r ON r.id = o.resource_id AND r.deleted = 0"
            + " WHERE r.store_id = #{storeId} AND o.occupy_type = 'DAY'"
            + " AND o.work_date >= #{from} AND o.work_date < #{toExclusive}")
    java.util.Set<Long> selectDayOccupiedResourceIds(@Param("storeId") Long storeId,
                                                     @Param("from") LocalDate from,
                                                     @Param("toExclusive") LocalDate toExclusive);

    /** FOR UPDATE 锁班次行（uk_emp_date 唯一锁点，第五阶段 §2.3）；仅事务内调用。 */
    @Select("SELECT s.employee_id AS employeeId, s.work_date AS workDate, s.start_time AS startTime,"
            + " s.end_time AS endTime, s.status FROM sch_schedule s"
            + " WHERE s.employee_id = #{employeeId} AND s.work_date = #{workDate} FOR UPDATE")
    com.petplatform.schedule.api.dto.ScheduleLockView lockScheduleRow(@Param("employeeId") Long employeeId,
                                                                      @Param("workDate") LocalDate workDate);

    /** 某店某日 WORKING 班次行（ADR-016 §7 可约查询员工候选读侧；uk_emp_date 每人每日≤1行）。 */
    @Select("SELECT s.employee_id AS employeeId, s.work_date AS workDate, s.start_time AS startTime,"
            + " s.end_time AS endTime, s.status FROM sch_schedule s"
            + " WHERE s.store_id = #{storeId} AND s.work_date = #{workDate} AND s.status = 'WORKING'")
    java.util.List<com.petplatform.schedule.api.dto.ScheduleLockView> selectWorkingShiftsOfStore(
            @Param("storeId") Long storeId, @Param("workDate") LocalDate workDate);

    /** 当日 TIME_SLOT 占用投影行。 */
    class SlotOccupyRow {
        private Long resourceId;
        private int slotNo;

        public Long getResourceId() { return resourceId; }
        public void setResourceId(Long resourceId) { this.resourceId = resourceId; }
        public int getSlotNo() { return slotNo; }
        public void setSlotNo(int slotNo) { this.slotNo = slotNo; }
    }
}
