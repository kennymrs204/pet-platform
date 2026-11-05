package com.petplatform.message.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 会话内消息一页（游标分页）。**list 顺序取决于查询方向**（与请求游标同源，不重排）：
 * 无游标 / beforeId → id DESC（最新在前），hasMore=更早还有；
 * afterId → id ASC（时间序，便于按顺序合并进现有记录），hasMore=更新还有。
 * 排序键与游标键同为 id（雪花插入序）——同一毫秒的消息也不重不漏。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessagePage {

    private List<MessageRef> list;
    private boolean hasMore;
}
