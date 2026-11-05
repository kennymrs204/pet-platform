package com.petplatform.fulfillment.api;

import com.petplatform.fulfillment.api.dto.LastServiceView;

import java.util.Collection;
import java.util.List;

/**
 * 履约服务记录只读查询（表 Owner：fulfillment 模块，ful_bath_record ∪ ful_walk_record）。
 * ADR-014：B 端宠物列表"最近服务"首选数据源；无记录的宠物静默跳过（缺数据返回空，不报错）。
 */
public interface ServiceRecordQueryApi {

    /**
     * 按宠物取最近一次已完成服务记录（max finished_at）。
     * 注意：本方法不按门店过滤，B 端门店卡片必须使用 {@link #lastServicesInAppointments}，
     * 不能把其他门店的履约记录当作本店最近服务。
     */
    List<LastServiceView> lastServices(Collection<Long> petIds);

    /**
     * 在指定预约集合内按宠物取最近一次已完成服务记录（max finished_at）。
     * 供 B 端卡片把履约摘要限制在当前门店预约内；两个集任一为空即短路。
     */
    List<LastServiceView> lastServicesInAppointments(Collection<Long> petIds,
                                                      Collection<Long> appointmentIds);

    /**
     * 按预约批量取洗澡/美容记录（ADR-038 洗护看板）。
     * 无记录的预约**不出现在 Map 里**（看板据此区分"未做"与"查询失败"，不把缺数据伪装成空记录）。
     */
    java.util.Map<Long, com.petplatform.fulfillment.api.dto.ServiceRecordBrief> bathRecordsByAppointments(
            Collection<Long> appointmentIds);

    /**
     * 按预约批量取遛狗记录（ADR-049 遛狗看板）。
     * 口径与洗澡版完全一致：无记录的预约**不出现在 Map 里**；区别只在落表（ful_walk_record）与出参形状
     * （有 duration_min、无 service_items/photos——遛狗表没有这两类列）。
     */
    java.util.Map<Long, com.petplatform.fulfillment.api.dto.WalkRecordBrief> walkRecordsByAppointments(
            Collection<Long> appointmentIds);

    /**
     * 按预约批量取遛狗 GPS 轨迹摘要（V16）。无轨迹的预约**不出现在 Map 里**（与履约记录同口径：
     * 调用方据此区分"没采集"与"读失败"，不把缺数据伪装成 0 公里）。
     * 距离/时长/点数直接取持久化值；只有 polyline 是读取时从有效点抽稀出来的展示数据。
     */
    java.util.Map<Long, com.petplatform.fulfillment.api.dto.WalkTrackView> walkTracksByAppointments(
            Collection<Long> appointmentIds);
}
