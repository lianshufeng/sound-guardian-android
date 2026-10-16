package com.anlan.guardian;

/** 不可变声音事件。类别来自模型，不等同于已发生危险。 */
public final class SoundEvent {
    public final String id, label, name;
    public final long start, end;
    public final boolean active;

    public SoundEvent(String id, String label, String name, long start, long end, boolean active) {
        this.id = id;
        this.label = label;
        this.name = name;
        this.start = start;
        this.end = end;
        this.active = active;
    }

    public int priority() {
        switch (label) {
            case "Alarm": case "Smoke detector, smoke alarm": case "Fire alarm": return 4;
            case "Screaming": case "Smash, crash": case "Glass": case "Shatter": return 3;
            case "Crying, sobbing": case "Baby cry, infant cry": case "Wail, moan":
            case "Groan": case "Cough": case "Throat clearing": case "Sneeze": return 2;
            default: return 1;
        }
    }
}
