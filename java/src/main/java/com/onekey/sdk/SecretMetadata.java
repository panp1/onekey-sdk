package com.onekey.sdk;

/** Key name and metadata of a secret. Never carries the value. */
public record SecretMetadata(
    String key, long version, String createdAt, String updatedAt, String description) {}
