package com.igmo.domain;

import java.time.Instant;
import lombok.Getter;

@Getter
public class Vote {

    private final String voterId;
    private final String optionId;
    private final Instant votedAt;

    private Vote(String voterId, String optionId, Instant votedAt) {
        this.voterId = voterId;
        this.optionId = optionId;
        this.votedAt = votedAt;
    }

    public static Vote of(String voterId, String optionId, Instant votedAt) {
        return new Vote(voterId, optionId, votedAt);
    }
}
