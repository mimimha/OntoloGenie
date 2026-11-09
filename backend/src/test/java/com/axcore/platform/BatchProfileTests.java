package com.axcore.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.analysis.service.AnalysisTimeoutJob;
import com.axcore.platform.audit.service.AuditRecorder;
import com.axcore.platform.batch.service.QueuedJobPoller;
import com.axcore.platform.source.service.CdcReloadJob;
import com.axcore.platform.source.service.CdcReloadService;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.WebApplicationContext;

// keep-alive는 테스트가 끝나도 JVM을 붙잡으므로 테스트에서만 끈다.
@SpringBootTest(properties = "spring.main.keep-alive=false")
@ActiveProfiles("batch")
@Import(TestcontainersConfiguration.class)
class BatchProfileTests {
    // 감시 작업이 시험 데이터에 다시 읽기를 넣지 않게 막는다.
    @MockitoBean CdcReloadJob cdcReloadJob;

    @Autowired ApplicationContext context;

    @Autowired DataSource dataSource;

    @Test
    void startsWithoutWebServer() {
        assertThat(context).isNotInstanceOf(WebApplicationContext.class);
    }

    @Test
    void connectsToDatabase() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.isValid(1)).isTrue();
        }
    }

    @Test
    void sharesAuditRecorderWithApi() {
        assertThat(context.getBean(AuditRecorder.class)).isNotNull();
    }

    @Test
    void pollsQueuedJobs() {
        assertThat(context.getBean(QueuedJobPoller.class)).isNotNull();
    }

    @Test
    void cleansUpStaleAnalysisRuns() {
        assertThat(context.getBean(AnalysisTimeoutJob.class)).isNotNull();
    }

    @Test
    void watchesCdcTablesForReload() {
        assertThat(context.getBean(CdcReloadService.class)).isNotNull();
    }
}
