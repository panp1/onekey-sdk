package com.onekey.sdk;

/** A project the token can see. {@code myRole} is null for runner tokens. */
public record Project(String id, String name, String createdAt, String updatedAt, String myRole) {}
