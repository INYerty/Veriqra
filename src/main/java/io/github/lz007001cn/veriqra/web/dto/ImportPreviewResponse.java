package io.github.lz007001cn.veriqra.web.dto;

import io.github.lz007001cn.veriqra.model.AutomationSource;
import io.github.lz007001cn.veriqra.service.importing.*;
import java.util.List;

public record ImportPreviewResponse(boolean readyToImport, List<MappedResultResponse> mappedResults,
                                    List<UnknownIdentityResponse> unknownIdentities,
                                    List<ImportIssueResponse> invalidEntries) {
    public static ImportPreviewResponse from(ImportPreview value) {
        return new ImportPreviewResponse(value.readyToImport(),
                value.mappedResults().stream().map(MappedResultResponse::from).toList(),
                value.unmappedIdentities().stream().map(UnknownIdentityResponse::from).toList(),
                value.invalidEntries().stream().map(ImportIssueResponse::from).toList());
    }

    public record MappedResultResponse(JUnitResultResponse result, AutomationIdentityResponse identity,
                                       AutomationMappingResponse mapping, TestCaseResponse testCase) {
        static MappedResultResponse from(ImportPreview.MappedResult value) {
            return new MappedResultResponse(JUnitResultResponse.from(value.result()),
                    AutomationIdentityResponse.from(value.identity()), AutomationMappingResponse.from(value.mapping()),
                    TestCaseResponse.from(value.testCase()));
        }
    }

    public record UnknownIdentityResponse(AutomationSource source, String namespace, String externalKey) {
        static UnknownIdentityResponse from(AutomationIdentityKey value) {
            return new UnknownIdentityResponse(value.source(), value.namespace(), value.externalKey());
        }
    }

    public record ImportIssueResponse(int entryIndex, String message) {
        static ImportIssueResponse from(ImportIssue value) {
            return new ImportIssueResponse(value.entryIndex(), value.message());
        }
    }
}
