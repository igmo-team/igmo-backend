package com.igmo.domain;

import java.util.UUID;
import lombok.Getter;

@Getter
public class Player {

    private final String id;
    private final Nickname nickname;
    private int score;

    public Player(String nickname) {
        this.id = UUID.randomUUID().toString();
        this.nickname = new Nickname(nickname);
        this.score = 0;
    }
}
