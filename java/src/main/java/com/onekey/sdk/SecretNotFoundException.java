package com.onekey.sdk;

/** The project has no secret with that key. */
public class SecretNotFoundException extends OneKeyException {
  private final String project;
  private final String key;

  public SecretNotFoundException(String project, String key) {
    super(404, "SECRET_NOT_FOUND", project + " has no secret \"" + key + "\"");
    this.project = project;
    this.key = key;
  }

  public String project() {
    return project;
  }

  public String key() {
    return key;
  }
}
