package com.migrationsentinel.payload.common;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.UUID;

public record ResponseMetadata(
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant timestamp,
        @com.fasterxml.jackson.annotation.JsonProperty("request_id") String requestId
) {
    public static ResponseMetadata now() {
        return new ResponseMetadata(Instant.now(), UUID.randomUUID().toString());
    }
}
