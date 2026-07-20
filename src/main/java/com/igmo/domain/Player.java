package com.igmo.domain;

import java.util.UUID;
import lombok.Getter;

@Getter
public class Player {

    private final String id;
    // 본인만 아는 자격증명. 소유자에게만 응답으로 전달하고 스냅샷에는 절대 노출하지 않는다.
    private final String secret;
    private final Nickname nickname;
    private int score;
    private boolean ready;

    public Player(String nickname) {
        this.id = UUID.randomUUID().toString();
        this.secret = UUID.randomUUID().toString();
        this.nickname = new Nickname(nickname);
        this.score = 0;
        this.ready = false;
    }

    public void changeReady(boolean ready) {
        this.ready = ready;
    }

    public void addScore(int delta) {
        this.score += delta;
    }
}
