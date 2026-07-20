package com.igmo.domain.exception;

public class ImagesNotReadyException extends RuntimeException {

    public ImagesNotReadyException() {
        super("모든 플레이어의 이미지가 생성된 후 게임을 진행할 수 있습니다.");
    }
}
