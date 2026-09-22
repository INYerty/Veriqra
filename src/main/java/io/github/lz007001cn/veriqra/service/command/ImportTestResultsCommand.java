package io.github.lz007001cn.veriqra.service.command;

import java.util.UUID;

/** Metadata plus the exact uploaded bytes used for SHA-256 idempotency. */
public record ImportTestResultsCommand(Long projectId, UUID requestKey, String sourceNamespace,
                                       String originalFilename, String runName, String environment,
                                       String buildVersion, byte[] payload) {
    public ImportTestResultsCommand {
        payload = payload == null ? null : payload.clone();
    }
    @Override public byte[] payload() { return payload == null ? null : payload.clone(); }
}
