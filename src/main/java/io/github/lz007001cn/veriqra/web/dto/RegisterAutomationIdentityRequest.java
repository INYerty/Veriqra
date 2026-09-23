package io.github.lz007001cn.veriqra.web.dto;

import io.github.lz007001cn.veriqra.model.AutomationSource;

public record RegisterAutomationIdentityRequest(AutomationSource source, String namespace, String externalKey) { }
