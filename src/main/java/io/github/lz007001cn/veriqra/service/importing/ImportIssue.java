package io.github.lz007001cn.veriqra.service.importing;

/** Entry index is one-based; zero means the report as a whole. */
public record ImportIssue(int entryIndex, String message) { }
