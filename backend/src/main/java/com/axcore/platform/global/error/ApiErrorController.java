package com.axcore.platform.global.error;

import com.axcore.platform.global.config.RequestIdFilter;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 컨트롤러 밖(필터, 서블릿 컨테이너)에서 난 오류도 공통 오류 형식으로 응답한다. Spring Boot 기본 {@code BasicErrorController}를 대신한다.
 */
@RestController
public class ApiErrorController implements ErrorController {

    @RequestMapping("${server.error.path:${error.path:/error}}")
    public ResponseEntity<ErrorResponse> handleError(HttpServletRequest request) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        CommonErrorCode errorCode =
                status instanceof Integer code
                        ? CommonErrorCode.fromStatus(code)
                        : CommonErrorCode.RESOURCE_NOT_FOUND;
        return ResponseEntity.status(errorCode.status())
                .body(
                        ErrorResponse.of(
                                errorCode, Map.of(), RequestIdFilter.currentRequestId(request)));
    }
}
