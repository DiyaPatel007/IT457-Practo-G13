package edu.dau.practolite.common.web;

import java.util.List;

/**
 * The single error format used by every endpoint:
 * {@code {"error":"SLOT_TAKEN","message":"...","details":["field: reason"]}}.
 */
public record ApiError(String error, String message, List<String> details) {

    public ApiError(String error, String message) {
        this(error, message, null);
    }
}
