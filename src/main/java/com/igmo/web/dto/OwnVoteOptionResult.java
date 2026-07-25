package com.igmo.web.dto;

// 투표 단계에서 각 플레이어에게 개인큐로 전달하는 본인 프롬프트 보기 식별자.
public record OwnVoteOptionResult(
        String roomCode,
        int roundNumber,
        String optionId
) {
}
