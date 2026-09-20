package io.github.lz007001cn.qatrack.service.importing;

import io.github.lz007001cn.qatrack.model.AutomationSource;

/** Exact, case-sensitive external identity. */
public record AutomationIdentityKey(AutomationSource source, String namespace, String externalKey) { }
