package com.petplatform.fulfillment.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 洗澡/美容履约记录（{@code ful_bath_record}）的写入入参（ADR-048 §6）。
 *
 * <p>字段与表列一一对应，<b>没有一项是推断出来的</b>：预约 id/宠物/执行人/服务与时点全部来自
 * "完成服务"那一次状态落定的返回值（{@code AppointmentTaskView}）。表上
 * {@code started_at}/{@code finished_at} 都是 NOT NULL，所以这条记录天然只能"完成时一次写全"——
 * 不存在"先建一条开始、完成时再补"的半成品形态，也就不需要为开始动作造一张中间表。
 *
 * @param appointmentId  必填，履约对象
 * @param petId          必填
 * @param employeeId     必填，实际执行人（= 预约上的 employee_id）
 * @param storeServiceId 必填，洗护表带这一列（遛狗表没有）
 * @param executorRole   必填，执行人当时角色（DDL 注释原话），如 GROOMER
 * @param byManager      店长/前台代录标记（DDL 语义"店长顶班"）
 * @param serviceItems   可选，执行项（前端勾选框已存在，本轮接上提交口）；null=不写该列
 * @param photos         可选，照片对象键列表；本轮 UI 未接上传，留 null
 */
@Data
@Builder
public class BathRecordDraft {

    private Long appointmentId;
    private Long petId;
    private Long employeeId;
    private Long storeServiceId;
    private String executorRole;
    private boolean byManager;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private List<String> serviceItems;
    private List<String> photos;
    private String remark;
}
