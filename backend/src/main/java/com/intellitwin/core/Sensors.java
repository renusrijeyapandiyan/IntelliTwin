package com.intellitwin.core;

import java.util.List;

/** The five monitored signals, in canonical order. */
public final class Sensors {
    public static final List<String> NAMES = List.of("temperature", "vibration", "pressure", "current", "speed");
    public static final int WINDOW = 30;
    public static final int MIN_SAMPLES = 10;

    private Sensors() {}
}
