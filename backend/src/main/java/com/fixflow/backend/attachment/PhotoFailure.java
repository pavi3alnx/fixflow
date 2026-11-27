package com.fixflow.backend.attachment;

import org.springframework.http.HttpStatus;

public class PhotoFailure extends RuntimeException {
    private final HttpStatus status;
    public PhotoFailure(HttpStatus status,String message) { super(message);this.status=status; }
    public HttpStatus status() { return status; }
    public static PhotoFailure storage() { return new PhotoFailure(HttpStatus.SERVICE_UNAVAILABLE,"Photo storage is unavailable. Please retry."); }
    public static PhotoFailure invalid() { return new PhotoFailure(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"A valid PNG or JPEG photo is required."); }
}
