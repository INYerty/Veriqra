package io.github.lz007001cn.veriqra.admin;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigInteger;

public record CreditSummary(@JsonSerialize(using=ToStringSerializer.class) BigInteger totalBalance,
                            @JsonSerialize(using=ToStringSerializer.class) BigInteger totalIssued,
                            @JsonSerialize(using=ToStringSerializer.class) BigInteger totalReclaimed,
                            long usersWithBalance) { }
