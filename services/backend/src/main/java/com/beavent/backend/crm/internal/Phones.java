package com.beavent.backend.crm.internal;

import java.util.regex.Pattern;

/** Phone numbers as people type them, made E.164 (Ethiopian local numbers get +251). */
public final class Phones {

    private static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{6,14}$");
    private static final Pattern ETHIOPIAN_LOCAL = Pattern.compile("^0[79][0-9]{8}$");
    private static final Pattern ETHIOPIAN_BARE = Pattern.compile("^251[79][0-9]{8}$");

    private Phones() {}

    /**
     * @return the number in E.164, {@code null} for blank input
     * @throws IllegalArgumentException when it is not a phone number
     */
    public static String normalize(String typed) {
        if (typed == null || typed.isBlank()) {
            return null;
        }
        String compact = typed.strip().replaceAll("[\\s\\-().]", "");
        if (compact.startsWith("00")) {
            compact = "+" + compact.substring(2);
        }
        if (ETHIOPIAN_LOCAL.matcher(compact).matches()) {
            compact = "+251" + compact.substring(1);
        } else if (ETHIOPIAN_BARE.matcher(compact).matches()) {
            compact = "+" + compact;
        }
        if (!E164.matcher(compact).matches()) {
            throw new IllegalArgumentException("Not a phone number: " + typed);
        }
        return compact;
    }
}
