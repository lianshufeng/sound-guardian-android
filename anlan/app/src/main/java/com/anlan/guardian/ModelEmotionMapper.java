package com.anlan.guardian;

import java.util.Locale;

/** 将模型的环境分类映射为日常拟人反应；守护事件在状态机中拥有更高优先级。 */
final class ModelEmotionMapper {
    private ModelEmotionMapper() { }

    static Emotion emotion(String label) {
        if (label == null || label.isEmpty()) return null;
        String value = label.toLowerCase(Locale.US);
        if (value.contains("speech") || value.contains("conversation") || value.contains("talk")) return Emotion.LISTENING;
        if (value.contains("music") || value.contains("song") || value.contains("singing") || value.contains("instrument")) return Emotion.CONTENT;
        if (value.contains("laugh") || value.contains("laughter") || value.contains("applause") || value.contains("cheer")) return Emotion.HAPPY;
        if (value.contains("alarm") || value.contains("siren") || value.contains("emergency")) return Emotion.ALERT;
        if (value.contains("cry") || value.contains("sobbing") || value.contains("baby") || value.contains("infant")) return Emotion.CARING;
        if (value.contains("cough") || value.contains("sneeze") || value.contains("groan") || value.contains("moan")) return Emotion.WORRIED;
        if (value.contains("knock") || value.contains("tap") || value.contains("footstep") || value.contains("door") || value.contains("bang")) return Emotion.CURIOUS;
        if (value.contains("dog") || value.contains("cat") || value.contains("bird") || value.contains("animal")) return Emotion.CURIOUS;
        if (value.contains("rain") || value.contains("water") || value.contains("wind") || value.contains("thunder")) return Emotion.CONTENT;
        if (value.contains("room") || value.contains("indoor") || value.contains("outdoor") || value.contains("ambient")
                || value.contains("silence") || value.contains("noise") || value.contains("static")) return Emotion.CALM;
        return Emotion.ATTENTIVE;
    }
}
