package io.github.lz007001cn.veriqra.service.importing;

import io.github.lz007001cn.veriqra.model.AutomationSource;

/** Exact, case-sensitive external identity. */
public record AutomationIdentityKey(AutomationSource source, String namespace, String externalKey) { }
