package com.petplatform.order.service;

import com.petplatform.common.result.PageResult;
import com.petplatform.order.api.RefundQueryApi;
import com.petplatform.order.api.dto.RefundView;
import com.petplatform.order.mapper.RefundMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 退款单只读查询（ADR-037 §3）。纯读，不做权限判定——门店过滤由 app 层按 storeCtx 传进来，
 * 与 {@code StoreOrderQueryService} 同一口径（order 模块看不到 X-Store-Id 与角色码）。
 */
@Service
public class RefundQueryService implements RefundQueryApi {

    private final RefundMapper refundMapper;

    public RefundQueryService(RefundMapper refundMapper) {
        this.refundMapper = refundMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RefundView> byOrder(Long orderId) {
        return orderId == null ? List.of() : refundMapper.selectViewsByOrder(orderId);
    }

    @Override
    @Transactional(readOnly = true)
    public RefundView get(Long refundId) {
        return refundId == null ? null : refundMapper.selectViewById(refundId);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<RefundView> page(String status, Long storeId, int pageNum, int pageSize) {
        int size = Math.min(Math.max(pageSize, 1), 100);
        int num = Math.max(pageNum, 1);
        long total = refundMapper.countViewPage(status, storeId);
        List<RefundView> list = total == 0 ? List.of()
                : refundMapper.selectViewPage(status, storeId, size, (long) (num - 1) * size);
        return PageResult.of(total, num, size, list);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> autoApprovableIds(java.time.LocalDateTime cutoff) {
        return cutoff == null ? List.of() : refundMapper.selectAutoApprovableIds(cutoff);
    }
}
