package io.github.lz007001cn.veriqra.admin;

import java.util.List;

public record Page<T>(List<T> items, long total, int page, int pageSize) {
    public Page { items = List.copyOf(items); }
}
