package com.onekey.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads project secrets from a OneKey server.
 *
 * <p>{@code url} is the client endpoint, for example {@code https://onekey.example.com} or, behind an
 * SSO proxy, {@code https://onekey.example.com/cli}. {@code token} is a project runner token
 * ({@code dbs_...}) or a personal token ({@code dpa_...}). {@link #fromEnv()} reads both from the
 * {@code ONEKEY_URL} and {@code ONEKEY_TOKEN} environment variables.
 */
public final class OneKeyClient {
  static final String VERSION = "0.1.1";
  private static final String API = "/api/v1";
  private static final ObjectMapper JSON = new ObjectMapper();

  private final String base;
  private final String token;
  private final Duration timeout;
  private final HttpClient http;

  public OneKeyClient(String url, String token) {
    this(url, token, Duration.ofSeconds(10));
  }

  public OneKeyClient(String url, String token, Duration timeout) {
    if (url == null || url.isBlank()) throw new IllegalArgumentException("OneKey url is required");
    if (token == null || token.isBlank()) throw new IllegalArgumentException("OneKey token is required");
    this.base = url.replaceAll("/+$", "");
    this.token = token;
    this.timeout = timeout;
    this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
  }

  /** Builds a client from {@code ONEKEY_URL} and {@code ONEKEY_TOKEN}. */
  public static OneKeyClient fromEnv() {
    return new OneKeyClient(System.getenv("ONEKEY_URL"), System.getenv("ONEKEY_TOKEN"));
  }

  /** Server identity as returned by {@code /health}: product, version, apiVersion, status. */
  public Map<String, String> health() {
    JsonNode data = get("/health", null);
    Map<String, String> out = new LinkedHashMap<>();
    data.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
    return out;
  }

  /** Projects the token can see. Needs a personal token; runner tokens get {@code AUTHORIZATION_DENIED}. */
  public List<Project> projects() {
    List<Project> out = new ArrayList<>();
    for (JsonNode p : get("/projects", null)) {
      out.add(new Project(text(p, "id"), text(p, "name"), text(p, "createdAt"), text(p, "updatedAt"), text(p, "myRole")));
    }
    return out;
  }

  /** Groups of a project. Groups organise secrets; they carry no permissions. */
  public List<Group> groups(String project) {
    List<Group> out = new ArrayList<>();
    for (JsonNode g : get("/environments", Map.of("project", project))) {
      out.add(new Group(text(g, "id"), text(g, "projectId"), text(g, "projectName"), text(g, "name"), text(g, "createdAt"), text(g, "updatedAt")));
    }
    return out;
  }

  /** Key names and metadata (never values) of every group in the project. */
  public List<SecretMetadata> secretNames(String project) {
    return secretNames(project, null);
  }

  /** Key names and metadata of one group (by name or id); {@code null} means every group. */
  public List<SecretMetadata> secretNames(String project, String group) {
    List<SecretMetadata> out = new ArrayList<>();
    for (Group g : groups(project)) {
      if (group != null && !group.equals(g.name()) && !group.equals(g.id())) continue;
      for (JsonNode s : get("/environments/" + encode(g.id()) + "/secrets", null)) {
        out.add(new SecretMetadata(text(s, "key"), s.path("version").asLong(), text(s, "createdAt"), text(s, "updatedAt"), text(s, "description")));
      }
    }
    return out;
  }

  /** Every secret value of the project, across all its groups. */
  public RuntimeSecrets runtime(String project) {
    JsonNode data = get("/projects/" + encode(project) + "/secrets/runtime", null);
    Map<String, String> entries = new LinkedHashMap<>();
    for (JsonNode e : data.path("entries")) entries.put(text(e, "key"), text(e, "value"));
    return new RuntimeSecrets(text(data, "project"), orEmpty(text(data, "environment")), orEmpty(text(data, "environmentId")), entries);
  }

  /** One secret value; throws {@link SecretNotFoundException} when the key does not exist. */
  public String secret(String project, String key) {
    return runtime(project).get(key).orElseThrow(() -> new SecretNotFoundException(project, key));
  }

  // ----- transport -----

  private JsonNode get(String path, Map<String, String> query) {
    StringBuilder url = new StringBuilder(base).append(API).append(path);
    if (query != null && !query.isEmpty()) {
      url.append('?');
      Iterator<Map.Entry<String, String>> it = query.entrySet().iterator();
      while (it.hasNext()) {
        Map.Entry<String, String> e = it.next();
        url.append(encode(e.getKey())).append('=').append(encode(e.getValue()));
        if (it.hasNext()) url.append('&');
      }
    }
    HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
        .timeout(timeout)
        .header("Authorization", "Bearer " + token)
        .header("Accept", "application/json")
        .header("User-Agent", "onekey-sdk-java/" + VERSION)
        .GET()
        .build();
    HttpResponse<String> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new OneKeyException(0, "NETWORK_ERROR", e.getMessage() == null ? e.toString() : e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new OneKeyException(0, "INTERRUPTED", "request interrupted");
    }
    return unwrap(response.statusCode(), response.body());
  }

  static JsonNode unwrap(int status, String body) {
    JsonNode envelope;
    try {
      envelope = JSON.readTree(body);
    } catch (IOException e) {
      throw new OneKeyException(status, status >= 200 && status < 300 ? "INVALID_RESPONSE" : "HTTP_ERROR", "OneKey responded with HTTP " + status);
    }
    if (status < 200 || status >= 300 || envelope == null || !envelope.path("success").asBoolean(false)) {
      JsonNode errors = envelope == null ? null : envelope.path("error");
      if (errors != null && errors.isObject() && errors.fields().hasNext()) {
        Map.Entry<String, JsonNode> first = errors.fields().next();
        throw new OneKeyException(status, first.getKey(), first.getValue().asText());
      }
      throw new OneKeyException(status, "HTTP_ERROR", "OneKey responded with HTTP " + status);
    }
    return envelope.path("data");
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }

  private static String encode(String segment) {
    return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
