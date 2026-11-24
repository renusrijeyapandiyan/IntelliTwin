package com.intellitwin.core;

/** One of the features that contributed most to a prediction (value is baseline-normalised). */
public record TopFeature(String name, double value, double zScore, double importance, double contribution,
                         String direction) {}
