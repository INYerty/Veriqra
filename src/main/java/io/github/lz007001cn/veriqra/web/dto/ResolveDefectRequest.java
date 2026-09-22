package io.github.lz007001cn.veriqra.web.dto;
import io.github.lz007001cn.veriqra.model.*;
import java.util.*;
public record ResolveDefectRequest(Integer expectedVersion, String resolutionNote) {
    public ResolveDefectRequest {
        new ExpectedVersionRequest(expectedVersion);
        Objects.requireNonNull(resolutionNote, "resolutionNote");
    }
}
