package com.chtholly.auth.model;

public enum IdentifierType {
    PHONE,
    EMAIL,
    HANDLE;

    public static IdentifierType fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("identifier type required");
        }
        return switch (value.toLowerCase()) {
            case "phone", "mobile" -> PHONE;
            case "email" -> EMAIL;
            case "handle", "username" -> HANDLE;
            default -> throw new IllegalArgumentException("Unsupported identifier type: " + value);
        };
    }
}
