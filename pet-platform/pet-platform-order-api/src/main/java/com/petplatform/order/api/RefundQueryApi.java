package com.petplatform.order.api;

import com.petplatform.common.result.PageResult;
import com.petplatform.order.api.dto.RefundView;

import java.util.List;

/**
 * 退款单只读查询（表 Owner=order，ADR-037 §3）。
 * 消费方：app 的 B 端退款面板与 C 端退款进度端点——两者都只做"读 + 归属/门店过滤"，
 * 权限判定在 app 层（order 模块看不到 storeCtx 与角色码）。
 */
public interface RefundQueryApi {

    /** 某订单的全部退款单（含被拒的），创建时间倒序；无则空列表。 */
    List<RefundView> byOrder(Long orderId);

    /** 单条退款单（审批前取判定字段用）；不存在返回 null。 */
    RefundView get(Long refundId);

    /**
     * 分页查退款单。
     *
     * @param status  状态过滤（如 APPLYING=待处理）；null/空=不限
     * @param storeId 门店过滤；null=不限门店（仅 ADMIN 上下文由上层显式传 null）
     */
    PageResult<RefundView> page(String status, Long storeId, int pageNum, int pageSize);

    /**
     * 超时自动退款候选：到点仍未被处理的 APPLYING 单 id（升序，单批上限 200）。
     * 消费方=app 的 {@code RefundAutoApproveJob}（定时编排归 app，order 只管查）。
     */
    List<Long> autoApprovableIds(java.time.LocalDateTime cutoff);
}
