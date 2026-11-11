package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.dto.ObjectLinkEvidenceResponse;
import com.axcore.platform.ontology.service.ObjectEvidenceQuery;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentObjectEvidenceToolsTests {
    private static final LoginAccount ASKER = new LoginAccount(7L, "planner", "PLANNER", false);

    private final ObjectEvidenceQuery evidence = mock(ObjectEvidenceQuery.class);
    private final AgentQuerySession session =
            new AgentQuerySession(ASKER, 11L, mock(AgentQueryRecorder.class), Clock.systemUTC());
    private final AgentObjectEvidenceTools tools = new AgentObjectEvidenceTools(session, evidence);

    @Test
    void returnsLinkEvidenceAsFound() {
        ObjectLinkEvidenceResponse found =
                new ObjectLinkEvidenceResponse(
                        Map.of("work_order_no", "WO-501"), "PR03", "PR-03", null, null, null);
        when(evidence.linkEvidence(ASKER, 301L, 91L)).thenReturn(found);

        Map<String, Object> reply = tools.linkEvidence(301L, 91L);

        assertThat(reply).containsEntry("resultCategory", "FOUND").containsEntry("data", found);
        AgentQueryResult result = session.results().getFirst();
        assertThat(result.toolCode()).isEqualTo(AgentObjectEvidenceTools.OBJECT_LINK_EVIDENCE);
        assertThat(result.sources()).containsExactly(Map.of("objectId", 301L));
    }

    @Test
    void treatsHiddenOrMissingLinkAsNoData() {
        when(evidence.linkEvidence(ASKER, 301L, 999L))
                .thenThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, Map.of()));

        assertThat(tools.linkEvidence(301L, 999L)).containsEntry("resultCategory", "NO_DATA");
    }
}
