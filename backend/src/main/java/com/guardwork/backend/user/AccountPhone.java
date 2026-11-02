package com.guardwork.backend.user;

/** Declared Vietnamese mobile contact; normalization does not prove possession. */
public final class AccountPhone {
    private static final String INPUT_REGEX = "^(0|\\+84)[35789][0-9]{8}$";

    private AccountPhone() {}

    public static String normalize(String value) {
        if (value == null) return null;
        String phone = value.trim();
        if (!phone.matches(INPUT_REGEX)) {
            throw new IllegalArgumentException("A Vietnamese mobile number is required");
        }
        return phone.startsWith("0") ? "+84" + phone.substring(1) : phone;
    }
}
