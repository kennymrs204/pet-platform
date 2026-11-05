package com.petplatform.fulfillment.api;

import com.petplatform.fulfillment.api.dto.BathRecordDraft;
import com.petplatform.fulfillment.api.dto.WalkRecordDraft;

/**
 * 履约记录写入口（ADR-048）——fulfillment 模块<b>第一次</b>有写侧。
 * 此前该模块只有 {@link ServiceRecordQueryApi}（读），两张 {@code ful_*_record} 表的 INSERT
 * 只存在于验收脚本里，等于"记录"这件事一直停在纸面模型上。
 *
 * <p><b>谁调</b>：app 装配层。因为"完成服务"要同时落两件事——预约状态
 * （{@code apt_appointment SERVING→COMPLETED}，appointment 表）与履约记录（本模块表）——
 * 而 appointment 与 fulfillment 之间没有、也不该有横向依赖（ArchUnit 跨模块只经 api，
 * 且让 appointment 反向写 fulfillment 表就是第二个 Owner）。所以由组合根在<b>同一个本地事务</b>里
 * 先推进预约、再写记录，任一步失败整体回滚：不允许出现"预约已完成但没有记录"或反之。
 *
 * <p><b>幂等靠调用方</b>：预约侧的 {@code SERVING→COMPLETED} 是条件更新，并发/重复点击只有一人改得动，
 * 拿到 {@code changed=true} 的那一次才会调用本接口。因此这里<b>不加</b>按 appointment 去重的条件插入——
 * 一张 append-only 的记录表上，"同一预约两条记录"应当是异常而非允许态，静默去重反而会藏住 bug。
 *
 * <p>表上没有 {@code uk_appointment} 唯一键（V1 既有结构，本轮零迁移），所以这层约束是流程性的；
 * 报告里如实标注：若将来接入重试投递或补录工具，需要改成真幂等。
 *
 * @return 新记录 id
 */
public interface ServiceRecordCommandApi {

    /**
     * 单条遛狗记录的照片上限。服务端硬闸（追加时检查），前端同值仅用于提前提示；
     * 取自"少量多图"的产品口径，与聊天单图上限不冲突。
     */
    int MAX_WALK_PHOTOS = 9;

    /** 单次轨迹提交的点数上限（服务端硬闸，前端同值仅作提前提示）。 */
    int MAX_TRACK_POINTS = 2000;

    /** 轨迹累计暂停时长上限（秒），与 12h 记录时长上限同量级。 */
    int MAX_TRACK_PAUSE_SEC = 12 * 3600;

    /** 洗澡/美容记录（{@code ful_bath_record}）。 */
    Long createBath(BathRecordDraft draft);

    /** 遛狗记录（{@code ful_walk_record}）。 */
    Long createWalk(WalkRecordDraft draft);

    /**
     * 给<b>已存在</b>的遛狗记录追加照片 key（append-only：只加不减，重复 key 自动去重）。
     *
     * <p>与 {@link #createWalk} 分成两个入口，是为了让"完成服务"的事务保持最小：
     * 照片由独立的写操作补，上传失败/对象校验失败都不会回滚或改写预约状态。
     * key 的归属与对象存在性由调用方（app 装配层）在调用前校验——本方法只做表级校验与并发串行。
     *
     * @return 追加后的完整 key 列表（原顺序 + 新增顺序）
     * @throws com.petplatform.common.error.BusinessException 记录不存在(10004)、参数为空/超上限(10001)
     */
    java.util.List<String> appendWalkPhotos(Long appointmentId, java.util.List<String> mediaKeys);

    /**
     * 保存一次遛狗的 GPS 轨迹（一次服务一条，**保存后不可覆盖**）。
     *
     * <p>入参是客户端原始坐标；服务端清洗（范围/时间序/重复点/跳点）后再派生
     * 距离、实际记录时长与有效点数，并连同有效点一起落库——客户端自称的里程不被采信。
     * 幂等：同一 {@code trackId} 重试返回已保存的轨迹；同一预约换一个 trackId 再提交 ⇒ 10005。
     *
     * @return 保存后的轨迹摘要（含抽稀 polyline）
     * @throws com.petplatform.common.error.BusinessException 参数/点数/坐标非法(10001)、重复提交(10005)、有效点不足(10001)
     */
    com.petplatform.fulfillment.api.dto.WalkTrackView saveWalkTrack(
            com.petplatform.fulfillment.api.dto.WalkTrackDraft draft);
}
