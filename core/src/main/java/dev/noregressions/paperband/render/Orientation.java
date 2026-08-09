package dev.noregressions.paperband.render;

/**
 * Page orientation for PDF output. Renderers that honour orientation should
 * swap the {@link PageSize} dimensions when {@link #LANDSCAPE} is requested.
 */
public enum Orientation { PORTRAIT, LANDSCAPE }
