package io.github.lz007001cn.qatrack.service.support;
import io.github.lz007001cn.qatrack.service.exception.ValidationException;
import java.util.Objects;
/** Optional scope for legacy calls; HTTP always supplies a positive project ID. */
public final class ProjectOwnership {
    private ProjectOwnership() { }
    public static void require(Long actual, Long expected) {
        if (expected != null && !Objects.equals(actual, expected))
            throw new ValidationException("Resource does not belong to the requested project");
    }
}
