package com.igmo.web.dto;

// 라운드가 끝난 뒤 공개하는 항목. 정답 항목도 같은 구조로 표현하며, 이때 player는 출제자, guessText는 정답 프롬프트다.
public record RoundResultView(
        PlayerView player,
        String guessText,
        boolean isAnswer,
        int voteCount,
        int roundScore
) {
}
