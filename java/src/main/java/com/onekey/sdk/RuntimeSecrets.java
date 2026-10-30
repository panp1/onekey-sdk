package com.onekey.sdk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Every secret of a project (all groups). {@link #toString()} never includes values. */
public final class RuntimeSecrets {
  private final String project;
  private final String environment;
  private final String environmentId;
  private final Map<String, String> entries;

  RuntimeSecrets(String project, String environment, String environmentId, Map<String, String> entries) {
    this.project = project;
    this.environment = environment;
    this.environmentId = environmentId;
    this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
  }

  public String project() {
    return project;
  }

  public String environment() {
    return environment;
  }

  public String environmentId() {
    return environmentId;
  }

  /** Read-only view of key to value. */
  public Map<String, String> entries() {
    return entries;
  }

  public Optional<String> get(String key) {
    return Optional.ofNullable(entries.get(key));
  }

  public boolean has(String key) {
    return entries.containsKey(key);
  }

  public Set<String> keys() {
    return entries.keySet();
  }

  /** A mutable copy for {@code ProcessBuilder.environment().putAll(...)}. */
  public Map<String, String> asEnv() {
    return new LinkedHashMap<>(entries);
  }

  @Override
  public String toString() {
    return "RuntimeSecrets{project=" + project + ", keys=" + entries.size() + "}";
  }
}
