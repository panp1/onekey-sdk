package com.onekey.sdk;

/** A group inside a project. Groups organise secrets; they carry no permissions. */
public record Group(
    String id, String projectId, String projectName, String name, String createdAt, String updatedAt) {}
