package com.osundosun.mupid.api.common.gmail;

public class GmailException extends RuntimeException {

    public GmailException() {
    }

    public GmailException(String message) {
        super(message);
    }

    public GmailException(Throwable cause) {
        super(cause);
    }

    public GmailException(String message, Throwable cause) {
        super(message, cause);
    }

    public GmailException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
    
}
