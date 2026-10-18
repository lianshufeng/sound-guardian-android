package com.anlan.guardian;

/** 表情只描述视觉，不承载声音识别或业务判断。 */
public enum Emotion {
    CALM("平静"), SMILE("微笑"), HAPPY("开心"), CONTENT("满足"),
    CURIOUS("好奇"), ATTENTIVE("注意"), LISTENING("聆听"), DOUBTFUL("疑惑"),
    THINKING("思考"), WAITING("等待"), STARTLED("吃惊"), SURPRISED("惊讶"),
    TENSE("紧张"), WORRIED("担心"), CARING("关切"), SAD("难过"),
    HURT("委屈"), CRYING("哭泣"), DROWSY("困倦"), SLEEPING("打盹"),
    WAKING("醒来"), RELIEVED("放松"), PLAYFUL("调皮"), ACKNOWLEDGE("收到"),
    ALERT("警觉"), UNAVAILABLE("暂不可用"), SHY("害羞"), NUZZLE("蹭手");

    public final String title;
    Emotion(String title) { this.title = title; }
}
