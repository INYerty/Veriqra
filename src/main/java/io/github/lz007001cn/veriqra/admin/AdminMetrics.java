package io.github.lz007001cn.veriqra.admin;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigInteger;

public record AdminMetrics(long users, long activeUsers, long disabledUsers, long admins,
                           long loginsToday, long failedLoginsToday, long rateLimitedToday,
                           long requestsToday, long clientErrorsToday, long serverErrorsToday,
                           long uniqueIpsToday,
                           @JsonSerialize(using=ToStringSerializer.class) BigInteger creditsIssuedToday,
                           @JsonSerialize(using=ToStringSerializer.class) BigInteger creditsReclaimedToday) { }
