package io.github.lz007001cn.veriqra.model;

public record MonthlyContribution(Long userId, String username, String displayName,
                                  Long score, Long acceptedTaskCount) { }
