package com.igmo.domain;

import com.igmo.exception.InvalidNicknameException;

public record Nickname(String value) {

    private static final int MIN_LENGTH = 2;
    private static final int MAX_LENGTH = 10;

    public Nickname {
        value = trim(value);
        validateLength(value);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static void validateLength(String value) {
        if (value.length() < MIN_LENGTH || value.length() > MAX_LENGTH) {
            throw new InvalidNicknameException(MIN_LENGTH, MAX_LENGTH);
        }
    }
}
