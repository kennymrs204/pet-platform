package com.petplatform.message.api;

/**
 * 会话参与方（单一常量源，防各处拼写漂移）。
 * CUSTOMER = 客户账号（usr_user）；STORE = 门店员工（任意有权限员工，门店级共享已读）。
 */
public final class ChatSide {

    public static final String CUSTOMER = "CUSTOMER";
    public static final String STORE = "STORE";

    public static boolean isCustomer(String side) {
        return CUSTOMER.equals(side);
    }

    public static boolean isStore(String side) {
        return STORE.equals(side);
    }

    private ChatSide() {
    }
}
