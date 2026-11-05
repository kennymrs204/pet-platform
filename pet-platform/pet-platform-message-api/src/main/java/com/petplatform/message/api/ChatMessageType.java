package com.petplatform.message.api;

/**
 * 消息类型（单一常量源，防各处拼写漂移）：
 * TEXT = 纯文本（默认；历史行与不传 messageType 的旧客户端均归一到此）；
 * IMAGE = 图片消息（content 固定 '[图片]'，media_key 引用对象存储，见 msg_message V13）。
 */
public final class ChatMessageType {

    public static final String TEXT = "TEXT";
    public static final String IMAGE = "IMAGE";

    public static boolean isImage(String type) {
        return IMAGE.equals(type);
    }

    private ChatMessageType() {
    }
}
