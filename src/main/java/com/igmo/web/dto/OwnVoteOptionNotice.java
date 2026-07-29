package com.igmo.web.dto;

import com.igmo.domain.OwnVoteOption;

// 투표 시작 시 각 플레이어에게 개인큐로 밀어주는 본인 보기 알림.
// ownImage: 이 라운드 이미지가 본인 것인지(출제자 여부). true면 투표 자체가 불가하다.
// optionId: 본인 추측 보기 id. 출제자는 추측이 없어 null이다.
public record OwnVoteOptionNotice(
        String roomCode,
        int roundNumber,
        boolean ownImage,
        boolean voteAllowed,
        String optionId
) {

    public static OwnVoteOptionNotice of(String roomCode, int roundNumber, OwnVoteOption ownVoteOption) {
        return new OwnVoteOptionNotice(
                roomCode,
                roundNumber,
                ownVoteOption.ownImage(),
                ownVoteOption.voteAllowed(),
                ownVoteOption.optionId()
        );
    }
}
