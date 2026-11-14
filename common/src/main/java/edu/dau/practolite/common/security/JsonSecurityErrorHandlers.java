package edu.dau.practolite.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.dau.practolite.common.web.ApiError;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/** Writes 401 and 403 responses in the same JSON error format as the rest of the API. */
public final class JsonSecurityErrorHandlers {

    private JsonSecurityErrorHandlers() {
    }

    public static AuthenticationEntryPoint unauthorized(ObjectMapper mapper) {
        return (request, response, ex) -> write(response, mapper, HttpServletResponse.SC_UNAUTHORIZED,
                new ApiError("UNAUTHORIZED", "A valid access token is required for this endpoint"));
    }

    public static AccessDeniedHandler forbidden(ObjectMapper mapper) {
        return (request, response, ex) -> write(response, mapper, HttpServletResponse.SC_FORBIDDEN,
                new ApiError("FORBIDDEN", "Your role is not allowed to use this endpoint"));
    }

    private static void write(HttpServletResponse response, ObjectMapper mapper, int status, ApiError body)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(mapper.writeValueAsString(body));
    }
}
