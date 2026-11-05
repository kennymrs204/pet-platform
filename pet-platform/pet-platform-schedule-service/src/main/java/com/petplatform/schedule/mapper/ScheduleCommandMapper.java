package com.petplatform.schedule.mapper;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;

/**
 * 排班写侧 SQL（ADR-034）。全部在 {@code sch_schedule} 自家表上，零跨模块 JOIN。
 *
 * <p>改与删都带 {@code AND store_id = #{storeId}}：授权主语必须在语句里再落一遍，
 * 光靠服务层校验等于把"后来者改漏一行校验"变成跨店写入。
 */
@Mapper
public interface ScheduleCommandMapper {

    /** 写侧投影行（比 {@code ScheduleQueryMapper.ScheduleRow} 多 store_id 与 source——两者都是判据）。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class ScheduleManagedRow {
        private Long id;
        private Long employeeId;
        private Long storeId;
        private LocalDate workDate;
        private LocalTime startTime;
        private LocalTime endTime;
        private String status;
    }

    /**
     * 一次把区间内这些员工**已有的班**全捞出来（含别的店排的），避免逐日逐人 SELECT。
     * 故意不加 store_id 条件：{@code uk_emp_date} 是全局的，撞键与否跟他店无关，漏看就会在插入时才炸。
     */
    @Select("<script>SELECT id, employee_id, store_id, work_date, start_time, end_time, status FROM sch_schedule"
            + " WHERE work_date BETWEEN #{startDate} AND #{endDate} AND employee_id IN"
            + " <foreach collection=\"employeeIds\" item=\"e\" open=\"(\" separator=\",\" close=\")\">#{e}</foreach>"
            + " ORDER BY employee_id ASC, work_date ASC</script>")
    List<ScheduleManagedRow> selectExisting(@Param("employeeIds") Collection<Long> employeeIds,
                                            @Param("startDate") LocalDate startDate,
                                            @Param("endDate") LocalDate endDate);

    @Select("SELECT id, employee_id, store_id, work_date, start_time, end_time, status FROM sch_schedule"
            + " WHERE id = #{scheduleId} AND store_id = #{storeId} LIMIT 1")
    ScheduleManagedRow selectInStore(@Param("storeId") Long storeId, @Param("scheduleId") Long scheduleId);

    @Select("SELECT id, employee_id, store_id, work_date, start_time, end_time, status FROM sch_schedule"
            + " WHERE employee_id = #{employeeId} AND work_date = #{workDate} LIMIT 1")
    ScheduleManagedRow selectByEmployeeDate(@Param("employeeId") Long employeeId, @Param("workDate") LocalDate workDate);

    @Insert("INSERT INTO sch_schedule (id, employee_id, store_id, work_date, start_time, end_time, status, source)"
            + " VALUES (#{id}, #{employeeId}, #{storeId}, #{workDate}, #{startTime}, #{endTime}, 'WORKING', #{source})")
    int insert(@Param("id") Long id, @Param("employeeId") Long employeeId, @Param("storeId") Long storeId,
               @Param("workDate") LocalDate workDate, @Param("startTime") LocalTime startTime,
               @Param("endTime") LocalTime endTime, @Param("source") String source);

    /** 只动时段：status 与 source 一律不碰（LEAVE 属请假那片；source 要保留"这批是批量生成的"这一事实）。 */
    @Update("UPDATE sch_schedule SET start_time = #{startTime}, end_time = #{endTime}"
            + " WHERE id = #{scheduleId} AND store_id = #{storeId}")
    int updateTime(@Param("storeId") Long storeId, @Param("scheduleId") Long scheduleId,
                   @Param("startTime") LocalTime startTime, @Param("endTime") LocalTime endTime);

    @Delete("DELETE FROM sch_schedule WHERE id = #{scheduleId} AND store_id = #{storeId}")
    int delete(@Param("storeId") Long storeId, @Param("scheduleId") Long scheduleId);
}
