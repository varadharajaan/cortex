package io.cortex.remediation.anomaly;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * HTTP error mapping for the direct anomaly query API.
 */
@RestControllerAdvice(assignableTypes = AnomalyController.class)
public class AnomalyControllerAdvice {

    private static final String VALIDATION_FAILED = "VALIDATION_FAILED";

    /**
     * Maps query validation failures to a stable 400 response.
     *
     * @param ex validation exception
     * @return JSON error response
     */
    @ExceptionHandler({
            IllegalArgumentException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<AnomalyErrorResponse> badRequest(final Exception ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new AnomalyErrorResponse(VALIDATION_FAILED, ex.getMessage()));
    }
}
