package com.igmo.domain;

public enum AutoPromptPrefix {
    HESITATING("망설이는"),
    INDECISIVE("우유부단한"),
    FOOLISH("바보 같은"),
    SLUGGISH("거북이족인"),
    TIMEOVER("시간 초과된"),
    CLUELESS("갈팡질팡하는"),
    DAWDLE("밍기적거리는"),
    SLOWPOKE("느려터진"),
    OVERTHINKING("생각만 많은"),
    PARALYZED("선택 장애"),
    LAST_MINUTE("뒤늦게 헐레벌떡"),
    CHICKEN("쫄보"),
    LOST_IN_THOUGHT("멍 때리는")
    ;

    private final String value;

    AutoPromptPrefix(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
