package com.petplatform.schedule.api;

import com.petplatform.schedule.api.dto.KennelResourceView;

import java.util.List;

/**
 * 门店资源台账（ADR-020 §4；表 Owner=schedule，sch_resource）。
 * 复用 ADR-015 的 {@link KennelResourceView}，不再新建同形 DTO。
 * 不提供删除：sch_resource_schedule 的占用行指向资源，物理删会造成占用悬空 → 用 status=DISABLED 停用。
 */
public interface StoreResourceApi {

    /** 本店资源（含 MAINTENANCE/DISABLED，管理视图需要看到停用项）；按 type→id 稳定排序。 */
    List<KennelResourceView> listInStore(Long storeId);

    /** 新增资源；type 必须命中枚举、name 非空且 ≤50 字，否则 10001。 */
    KennelResourceView create(Long storeId, String type, String name);

    /** 改名/改状态；跨店或不存在返回 null（调用方按 10004，与 ADR-015 防探测同口径）。 */
    KennelResourceView update(Long storeId, Long resourceId, String name, String status);
}
