package com.petplatform.appointment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.petplatform.appointment.api.dto.TimeoutAppointmentView;
import com.petplatform.appointment.domain.AppointmentEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AppointmentCommandMapper extends BaseMapper<AppointmentEntity> {

    /** 支付成功推进（条件更新先到先得）。 */
    @Update("UPDATE apt_appointment SET status = 'CONFIRMED', version = version + 1"
            + " WHERE id = #{id} AND deleted = 0 AND status = 'PENDING_PAYMENT'")
    int updateConfirm(@Param("id") Long id);

    @Update("UPDATE apt_appointment SET status = 'CANCELLED', cancel_by_role = 'SYSTEM',"
            + " cancelled_reason = '支付超时自动取消', version = version + 1"
            + " WHERE id = #{id} AND deleted = 0 AND status = 'PENDING_PAYMENT'")
    int updateCancelTimeout(@Param("id") Long id);

    /**
     * 到店签到（ADR-042）：CONFIRMED→WAIT_SERVICE，条件更新先到先得。
     * 刻意**不写** {@code actual_start_at}——那列语义是"服务实际开始"，属任务链 task:start，
     * 签到只表示"人到了店里"，库里没有到店时间列，也不借用语义不同的列凑数。
     */
    @Update("UPDATE apt_appointment SET status = 'WAIT_SERVICE', version = version + 1"
            + " WHERE id = #{id} AND deleted = 0 AND status = 'CONFIRMED'")
    int updateCheckin(@Param("id") Long id);

    /** 用户取消（ADR-023）：按快照前态条件更新，0 行=并发漂移由调用方 50003。 */
    @Update("UPDATE apt_appointment SET status = 'CANCELLED', cancel_by_role = 'CUSTOMER',"
            + " cancelled_reason = #{reason}, version = version + 1"
            + " WHERE id = #{id} AND deleted = 0 AND status = #{fromStatus}")
    int updateCancelByUser(@Param("id") Long id, @Param("fromStatus") String fromStatus,
                           @Param("reason") String reason);

    /**
     * 店长分配/改派服务人员（ADR-048 §7）。<b>不改 status</b>——分配只换执行人，履约进度由
     * {@link #updateStartService}/{@link #updateFinishService} 推进；可分配的状态窗是
     * {@code CONFIRMED|WAIT_SERVICE}，外加一个<b>窄口径</b>：SERVING 且执行人从无人 → 有人的首派
     * （2026-10-27 履约轮补记——"开始不要求已指派"留下的 SERVING 无执行人单原本既不能完成也不能改派，
     * 是死锁；履约记录还没写，此时补上执行人不存在"谁做的"失据。已有执行人再动仍是改派，被
     * {@code AppointmentTaskService} 的守卫拦在 SQL 之前）。
     *
     * <p>为什么这里要带 {@code version}：状态不变 ⇒ 状态 CAS 保证不了"两个店长同时改派只有一人成功"，
     * 必须比版本号。0 行由调用方回读分辨三种情况：已是目标员工（幂等成功）、状态已推进（50004/50003）、
     * 被别人抢改（50003 让前端刷新重试）。
     */
    @Update("UPDATE apt_appointment SET employee_id = #{employeeId}, version = version + 1"
            + " WHERE id = #{id} AND deleted = 0 AND version = #{version}"
            + " AND status IN ('CONFIRMED','WAIT_SERVICE','SERVING')")
    int updateAssign(@Param("id") Long id, @Param("employeeId") Long employeeId,
                     @Param("version") Integer version);

    /**
     * 开始服务（ADR-048 §13）：{@code CONFIRMED|WAIT_SERVICE → SERVING} 并写 {@code actual_start_at}。
     *
     * <p>签到（{@link #updateCheckin}）刻意没写这列——"人到店"与"服务开始"是两件事，
     * 那列的语义一直留给这里（ADR-042 §刻意不做 ①）。允许从 CONFIRMED 直接开始：
     * 有些门店不做签到动作，卡成"必须先签到"是凭空加流程。
     *
     * <p>托管单不进这条通道：它的 SERVING 由 {@code AppointmentQueryMapper.updateStartBoarding}
     * （WHERE status='CONFIRMED'）在"办理入住"时写，语义是入住而非开始服务，两边抢同一状态会造出
     * 第二个真相——调用方 {@code AppointmentTaskService} 先按 boarding_checkin_date 拒掉。
     */
    @Update("UPDATE apt_appointment SET status = 'SERVING', actual_start_at = NOW(3), version = version + 1"
            + " WHERE id = #{id} AND deleted = 0 AND status IN ('CONFIRMED','WAIT_SERVICE')")
    int updateStartService(@Param("id") Long id);

    /** 完成服务（ADR-048 §14）：{@code SERVING → COMPLETED} 并写 {@code actual_end_at}。0 行=未开始/已完成/已取消。 */
    @Update("UPDATE apt_appointment SET status = 'COMPLETED', actual_end_at = NOW(3), version = version + 1"
            + " WHERE id = #{id} AND deleted = 0 AND status = 'SERVING'")
    int updateFinishService(@Param("id") Long id);

    /**
     * 已付预约提交取消申请（ADR-043 §2）：CONFIRMED→CANCEL_REQUESTED。
     * <b>只改状态，不释放占用、不动订单/退款</b>——占用要一直占到门店做出决定为止，
     * 否则店长拒绝时这条预约恢复成的"可服务状态"已经没有台位了。
     */
    @Update("UPDATE apt_appointment SET status = 'CANCEL_REQUESTED', cancel_by_role = 'CUSTOMER',"
            + " cancelled_reason = #{reason}, version = version + 1"
            + " WHERE id = #{id} AND deleted = 0 AND status = 'CONFIRMED'")
    int updateCancelRequest(@Param("id") Long id, @Param("reason") String reason);

    /** 退款成功后落定取消（ADR-043 §4）：CANCEL_REQUESTED→CANCELLED。0 行=已被处理，调用方幂等忽略。 */
    @Update("UPDATE apt_appointment SET status = 'CANCELLED', version = version + 1"
            + " WHERE id = #{id} AND deleted = 0 AND status = 'CANCEL_REQUESTED'")
    int updateCancelLand(@Param("id") Long id);

    /**
     * 退款被拒恢复原态（ADR-043 §3）：CANCEL_REQUESTED→CONFIRMED。
     * 恢复目标写死 CONFIRMED 是安全的——进入 CANCEL_REQUESTED 的唯一入口就是上面那条
     * {@code WHERE status='CONFIRMED'} 的条件更新，"申请前状态"由 CAS 前置条件本身记录，
     * 不再另设快照列（那是冗余状态）。
     */
    @Update("UPDATE apt_appointment SET status = 'CONFIRMED', cancel_by_role = NULL,"
            + " cancelled_reason = NULL, version = version + 1"
            + " WHERE id = #{id} AND deleted = 0 AND status = 'CANCEL_REQUESTED'")
    int updateCancelRestore(@Param("id") Long id);

    /** 改期 INSTANT（ADR-026 §3/§4）：换日期时段员工+重置支付时限，仅未付。 */
    @Update("UPDATE apt_appointment SET service_date = #{serviceDate}, start_time = #{startTime},"
            + " end_time = #{endTime}, employee_id = #{employeeId}, expire_at = #{expireAt},"
            + " version = version + 1"
            + " WHERE id = #{id} AND user_id = #{userId} AND deleted = 0 AND status = 'PENDING_PAYMENT'")
    int updateModifyInstant(@Param("id") Long id, @Param("userId") Long userId,
                            @Param("serviceDate") java.time.LocalDate serviceDate,
                            @Param("startTime") java.time.LocalTime startTime,
                            @Param("endTime") java.time.LocalTime endTime,
                            @Param("employeeId") Long employeeId,
                            @Param("expireAt") java.time.LocalDateTime expireAt);

    /** 改期 MULTI_DAY：换入住/离店区间（service_date 联动=入住日）+重置时限。 */
    @Update("UPDATE apt_appointment SET boarding_checkin_date = #{checkin},"
            + " boarding_checkout_date = #{checkout}, service_date = #{checkin}, expire_at = #{expireAt},"
            + " version = version + 1"
            + " WHERE id = #{id} AND user_id = #{userId} AND deleted = 0 AND status = 'PENDING_PAYMENT'")
    int updateModifyBoarding(@Param("id") Long id, @Param("userId") Long userId,
                             @Param("checkin") java.time.LocalDate checkin,
                             @Param("checkout") java.time.LocalDate checkout,
                             @Param("expireAt") java.time.LocalDateTime expireAt);

    @Select("SELECT a.id AS appointmentId, a.order_id AS orderId, a.store_id AS storeId, a.pet_id AS petId,"
            + " (a.boarding_checkin_date IS NOT NULL) AS multiDay"
            + " FROM apt_appointment a WHERE a.deleted = 0 AND a.status = 'PENDING_PAYMENT'"
            + " AND a.order_id IS NOT NULL AND a.expire_at < #{now} ORDER BY a.expire_at ASC LIMIT #{limit}")
    List<TimeoutAppointmentView> scanTimeout(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /** C7 条3：员工当日占用区间（非取消非过期语义由调用方比较；buffer 由调用方外扩）。 */
    @Select("SELECT a.start_time, a.end_time FROM apt_appointment a"
            + " WHERE a.deleted = 0 AND a.employee_id = #{employeeId} AND a.service_date = #{date}"
            + " AND a.status NOT IN ('CANCELLED') AND a.start_time IS NOT NULL")
    List<TimeRangeRow> selectEmployeeBusyRanges(@Param("employeeId") Long employeeId,
                                                @Param("date") LocalDate date);

    /** C9：同宠物同服务 24h 内未完成同类预约查重。 */
    @Select("SELECT COUNT(*) FROM apt_appointment a WHERE a.deleted = 0 AND a.pet_id = #{petId}"
            + " AND a.store_service_id = #{storeServiceId} AND a.status NOT IN ('CANCELLED','COMPLETED')"
            + " AND a.created_at > #{since}")
    long countRecentSameService(@Param("petId") Long petId, @Param("storeServiceId") Long storeServiceId,
                                @Param("since") LocalDateTime since);

    /** 员工占用时段投影。 */
    class TimeRangeRow {
        private java.time.LocalTime startTime;
        private java.time.LocalTime endTime;

        public java.time.LocalTime getStartTime() { return startTime; }
        public void setStartTime(java.time.LocalTime t) { this.startTime = t; }
        public java.time.LocalTime getEndTime() { return endTime; }
        public void setEndTime(java.time.LocalTime t) { this.endTime = t; }
    }
}
