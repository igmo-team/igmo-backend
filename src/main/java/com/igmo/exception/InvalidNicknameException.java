package com.igmo.exception;

public class InvalidNicknameException extends RuntimeException {

    public InvalidNicknameException(int minLength, int maxLength) {
        super("닉네임은 " + minLength + "자 이상 " + maxLength + "자 이하여야 합니다.");
    }
}
