package com.onekey.sdk;

/**
 * An error response from OneKey, or a transport failure. {@code status} is the HTTP status
 * (0 for transport errors); {@code code} is the stable code from the response envelope, for
 * example {@code AUTHORIZATION_DENIED} or {@code PROJECT_NOT_FOUND}. Branch on the code, not
 * on the message.
 */
public class OneKeyException extends RuntimeException {
  private final int status;
  private final String code;

  public OneKeyException(int status, String code, String message) {
    super(code + " (" + status + "): " + message);
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }
}
