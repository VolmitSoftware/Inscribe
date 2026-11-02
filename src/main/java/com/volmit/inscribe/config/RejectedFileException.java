package com.volmit.inscribe.config;

import java.io.IOException;

public final class RejectedFileException extends IOException {
    private final String file;
    private final String reason;

    public RejectedFileException(String file, Throwable cause) {
        this(file, reason(cause), cause);
    }

    public RejectedFileException(String file, String reason, Throwable cause) {
        super(file + ": " + reason, cause);
        this.file = file;
        this.reason = reason;
    }

    public String file() {
        return file;
    }

    public String reason() {
        return reason;
    }

    private static String reason(Throwable failure) {
        String message = null;
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
        }
        return message == null ? failure.getClass().getSimpleName() : message;
    }
}
