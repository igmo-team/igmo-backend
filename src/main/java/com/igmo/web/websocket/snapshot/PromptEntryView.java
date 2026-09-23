package com.igmo.web.websocket.snapshot;

import com.igmo.domain.PromptEntryStatus;
import com.igmo.web.PlayerView;

public record PromptEntryView(
        PlayerView player,
        PromptEntryStatus status
) {
}
