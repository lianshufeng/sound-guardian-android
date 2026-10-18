package com.anlan.guardian;

/** 明确命令先本地处理，普通文字不能触发设置；纯策略可单测。 */
final class ConversationRouter {
    enum Action { END, CARE_OFF, SNOOZE, BEDTIME, TIME, WEATHER, HELP, IDENTITY, MODEL }
    static Action route(String text) {
        String value = CareSession.normalize(text);
        if (matches(value, "停止", "请停止", "停止说话", "取消", "取消对话", "退出", "退出对话", "结束", "结束对话", "不聊了", "不聊了再见", "再见", "谢谢", "谢谢你", "好的谢谢", "谢谢先这样吧", "今天就到这里再见", "先这样吧", "安静", "别听了", "不用了", "不用", "没事", "我没事", "我没事不用担心", "是电视", "电视的声音", "晚安", "我要睡了")) return Action.END;
        if (matches(value, "停止关怀", "关闭主动关怀", "不要提醒了")) return Action.CARE_OFF;
        if (matches(value, "稍后", "等会儿", "晚点")) return Action.SNOOZE;
        if (matches(value, "今天晚点睡", "今晚晚点睡")) return Action.BEDTIME;
        if (matches(value, "现在几点", "现在几点了", "几点了", "告诉我时间")) return Action.TIME;
        if (matches(value, "天气", "天气怎么样", "今天天气", "今天天气怎么样", "外面冷吗", "外面天气怎么样")) return Action.WEATHER;
        if (matches(value, "需要帮助", "帮帮我", "需要", "帮我打电话")) return Action.HELP;
        if (matches(value, "你是谁", "你叫什么", "你叫什么名字")) return Action.IDENTITY;
        return Action.MODEL;
    }
    static boolean story(String text) { return text.contains("故事"); }
    private static boolean matches(String value, String... values) { for (String candidate : values) if (value.equals(candidate)) return true; return false; }
}
