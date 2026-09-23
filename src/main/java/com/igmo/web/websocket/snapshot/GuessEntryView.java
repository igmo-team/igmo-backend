package com.igmo.web.websocket.snapshot;

import com.igmo.web.PlayerView;

public record GuessEntryView(
        PlayerView player,
        boolean submitted
) {
}
