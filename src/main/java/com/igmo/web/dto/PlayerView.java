package com.igmo.web.dto;

import com.igmo.domain.Player;

public record PlayerView(String id, String nickname, int score) {

    public static PlayerView from(Player player) {
        return new PlayerView(player.getId(), player.getNickname().value(), player.getScore());
    }
}
