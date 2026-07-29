package com.igmo.domain;

// 투표 단계에서 각 플레이어에게 개인적으로 알려줄 본인 보기 정보.
public record OwnVoteOption(boolean ownImage, boolean voteAllowed, String optionId) {

    // 출제자는 자신의 이미지라 어떤 보기에도 투표할 수 없다.
    public static OwnVoteOption forQuestioner() {
        return new OwnVoteOption(true, false, null);
    }

    // 추측자는 본인 보기 id를 받아 해당 보기만 선택 불가 처리한다.
    public static OwnVoteOption forGuesser(String optionId) {
        return new OwnVoteOption(false, true, optionId);
    }

    public static OwnVoteOption forPerfectGuesser(String optionId) {
        return new OwnVoteOption(false, false, optionId);
    }
}
