package io.github.lz007001cn.veriqra.model;

/** Current persisted row; no business behavior. Generated fields come from MySQL. */
public record ProjectCounter(
        Long projectId,
        CounterEntityType entityType,
        Long nextValue) { }
