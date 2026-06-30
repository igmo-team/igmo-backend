package com.igmo.domain;

import java.util.UUID;
import lombok.Getter;

@Getter
public class Player {

    private final String id;
    private final String nickname;
    private int score;

    public Player(String nickname) {
        this.id = UUID.randomUUID().toString();
        this.nickname = nickname;
        this.score = 0;
    }
}
