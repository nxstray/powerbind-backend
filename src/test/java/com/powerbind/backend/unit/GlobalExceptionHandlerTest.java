package com.powerbind.backend.unit;

import com.powerbind.backend.data.ApiResponse;
import com.powerbind.backend.global.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.junit.jupiter.api.Assertions.*;

// Regression guard for the 500-instead-of-4xx bug found by the OWASP ZAP scan:
// the catch-all @ExceptionHandler(Exception.class) used to swallow Spring's own
// client-error exceptions, so a GET on a POST-only endpoint answered 500.
@DisplayName("Unit Test (global exception handler)")
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    private void assertError(ResponseEntity<ApiResponse<Void>> response, int expectedStatus) {
        assertEquals(expectedStatus, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().isSuccess());
        assertNotNull(response.getBody().getError());
    }

    @Test
    @DisplayName("TC-UNIT-GEH-01 wrong HTTP method maps to 405, not 500")
    void methodNotSupported_shouldReturn405() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleMethodNotSupported(new HttpRequestMethodNotSupportedException("GET"));

        assertError(response, 405);
        assertTrue(response.getBody().getError().contains("GET"));
    }

    @Test
    @DisplayName("TC-UNIT-GEH-02 unsupported content type maps to 415")
    void unsupportedMediaType_shouldReturn415() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleUnsupportedMediaType(new HttpMediaTypeNotSupportedException("text/csv"));

        assertError(response, 415);
    }

    @Test
    @DisplayName("TC-UNIT-GEH-03 missing request parameter maps to 400")
    void missingParameter_shouldReturn400() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBadInput(new MissingServletRequestParameterException("since", "String"));

        assertError(response, 400);
    }

    @Test
    @DisplayName("TC-UNIT-GEH-04 unknown path maps to 404, not 500")
    void unknownResource_shouldReturn404() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleNoResourceFound(new NoResourceFoundException(HttpMethod.GET, "/api/does-not-exist"));

        assertError(response, 404);
    }

    @Test
    @DisplayName("TC-UNIT-GEH-05 access denied maps to 403")
    void accessDenied_shouldReturn403() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleAccessDenied(new AccessDeniedException("nope"));

        assertError(response, 403);
    }

    @Test
    @DisplayName("TC-UNIT-GEH-06 genuinely unexpected errors still map to 500")
    void unexpectedError_shouldStillReturn500() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleGeneric(new IllegalStateException("boom"));

        assertError(response, 500);
    }
}
