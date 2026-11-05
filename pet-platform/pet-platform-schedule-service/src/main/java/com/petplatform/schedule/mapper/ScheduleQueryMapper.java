package com.petplatform.schedule.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 排班只读投影（不建实体，纯读 sch_schedule；表物理删除无 deleted 列，P2 约定）。
 */
@Mapper
public interface ScheduleQueryMapper {

    @Select("""
            <script>
            SELECT s.id, s.employee_id, s.work_date, s.start_time, s.end_time, s.status, s.leave_status
            FROM sch_schedule s
            WHERE s.store_id = #{storeId} AND s.work_date BETWEEN #{startDate} AND #{endDate}
            <if test="employeeId != null"> AND s.employee_id = #{employeeId}</if>
            ORDER BY s.work_date ASC, s.start_time ASC, s.employee_id ASC
            LIMIT 500
            </script>
            """)
    List<ScheduleRow> selectBetween(@Param("storeId") Long storeId,
                                    @Param("startDate") LocalDate startDate,
                                    @Param("endDate") LocalDate endDate,
                                    @Param("employeeId") Long employeeId);

    @Select("""
            SELECT COUNT(*) FROM sch_schedule
            WHERE store_id = #{storeId} AND work_date = #{workDate} AND status = 'WORKING'
            """)
    long countOnDuty(@Param("storeId") Long storeId, @Param("workDate") LocalDate workDate);

    /** 只读投影行（map-underscore-to-camel 自动映射）。 */
    class ScheduleRow {
        private Long id;
        private Long employeeId;
        private LocalDate workDate;
        private LocalTime startTime;
        private LocalTime endTime;
        private String status;
        private String leaveStatus;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getEmployeeId() { return employeeId; }
        public void setEmployeeId(Long employeeId) { this.employeeId = employeeId; }
        public LocalDate getWorkDate() { return workDate; }
        public void setWorkDate(LocalDate workDate) { this.workDate = workDate; }
        public LocalTime getStartTime() { return startTime; }
        public void setStartTime(LocalTime startTime) { this.startTime = startTime; }
        public LocalTime getEndTime() { return endTime; }
        public void setEndTime(LocalTime endTime) { this.endTime = endTime; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getLeaveStatus() { return leaveStatus; }
        public void setLeaveStatus(String leaveStatus) { this.leaveStatus = leaveStatus; }
    }
}
