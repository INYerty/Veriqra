package io.github.lz007001cn.qatrack.service.command;

/** Raw report bytes are copied so preview cannot observe caller mutation. */
public record AnalyzeTestImportCommand(Long projectId, String sourceNamespace, byte[] payload) {
    public AnalyzeTestImportCommand {
        payload = payload == null ? null : payload.clone();
    }
    @Override public byte[] payload() { return payload == null ? null : payload.clone(); }
}
