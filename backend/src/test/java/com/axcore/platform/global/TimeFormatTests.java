package com.axcore.platform.global;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axcore.platform.global.response.ApiResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 시각은 시간대가 붙은 ISO 8601 문자열로 주고받는다 (개발 가이드 8.4). */
// 응답 형식만 본다. 로그인·CSRF 검사는 AuthApiTests가 실제 보안 설정으로 확인한다.
@WebMvcTest(
        controllers = TimeFormatTests.TestController.class,
        excludeAutoConfiguration = {
            SecurityAutoConfiguration.class,
            ServletWebSecurityAutoConfiguration.class,
            SecurityFilterAutoConfiguration.class
        })
@Import(TimeFormatTests.TestController.class)
class TimeFormatTests {

    @Autowired MockMvc mockMvc;

    @Test
    void writesInstantAsUtcIsoString() throws Exception {
        mockMvc.perform(get("/test/time"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.at").value("2026-10-28T03:15:30Z"));
    }

    @Test
    void writesOffsetDateTimeWithItsOffset() throws Exception {
        mockMvc.perform(get("/test/offset-time"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.at").value("2026-10-28T12:15:30+09:00"));
    }

    @Test
    void readsIsoStringWithOffsetAsSameInstant() throws Exception {
        mockMvc.perform(
                        post("/test/time")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"at\": \"2026-10-28T12:15:30+09:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.at").value("2026-10-28T03:15:30Z"));
    }

    @Test
    void rejectsTimeWithoutZone() throws Exception {
        mockMvc.perform(
                        post("/test/time")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"at\": \"2026-10-28T12:15:30\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @RestController
    @RequestMapping("/test")
    static class TestController {

        @GetMapping("/time")
        ApiResponse<InstantBody> time() {
            return ApiResponse.of(new InstantBody(Instant.parse("2026-10-28T03:15:30Z")));
        }

        @GetMapping("/offset-time")
        ApiResponse<OffsetBody> offsetTime() {
            return ApiResponse.of(
                    new OffsetBody(OffsetDateTime.parse("2026-10-28T12:15:30+09:00")));
        }

        @PostMapping("/time")
        ApiResponse<InstantBody> echo(@RequestBody InstantBody body) {
            return ApiResponse.of(body);
        }
    }

    record InstantBody(Instant at) {}

    record OffsetBody(OffsetDateTime at) {}
}
