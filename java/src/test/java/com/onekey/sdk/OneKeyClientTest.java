package com.onekey.sdk;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class OneKeyClientTest {
  static final String TOKEN = "dbs_test_token";
  static HttpServer server;
  static String base;
  static OneKeyClient client;

  static final Map<String, String> ROUTES = Map.of(
      "/cli/api/v1/health", "{\"product\":\"onekey\",\"version\":\"0.7.14\",\"apiVersion\":\"v1\",\"status\":\"ok\"}",
      "/cli/api/v1/projects", "[{\"id\":\"prj_1\",\"name\":\"payments\",\"createdAt\":\"t\",\"updatedAt\":\"t\",\"myRole\":\"developer\"}]",
      "/cli/api/v1/environments?project=payments", "[{\"id\":\"env_1\",\"projectId\":\"prj_1\",\"projectName\":\"payments\",\"name\":\"production\",\"createdAt\":\"t\",\"updatedAt\":\"t\"}]",
      "/cli/api/v1/environments/env_1/secrets", "[{\"key\":\"API_KEY\",\"version\":2,\"createdAt\":\"t\",\"updatedAt\":\"t\",\"description\":\"Stripe\"}]",
      "/cli/api/v1/projects/payments/secrets/runtime", "{\"project\":\"payments\",\"environment\":\"production\",\"environmentId\":\"env_1\",\"entries\":[{\"key\":\"API_KEY\",\"value\":\"sk_test\"},{\"key\":\"DB-URL\",\"value\":\"postgres://x\"}]}");

  @BeforeAll
  static void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      String path = exchange.getRequestURI().toString();
      String auth = exchange.getRequestHeaders().getFirst("Authorization");
      int status;
      String body;
      if (!("Bearer " + TOKEN).equals(auth)) {
        status = 401; body = "{\"success\":false,\"error\":{\"AUTHENTICATION_INVALID\":\"Sign in.\"}}";
      } else if (path.equals("/cli/api/v1/projects/missing/secrets/runtime")) {
        status = 404; body = "{\"success\":false,\"error\":{\"PROJECT_NOT_FOUND\":\"No such project.\"}}";
      } else if (path.equals("/cli/api/v1/broken")) {
        status = 200; body = "not json";
      } else if (ROUTES.containsKey(path)) {
        status = 200; body = "{\"success\":true,\"message\":\"OK\",\"data\":" + ROUTES.get(path) + "}";
      } else {
        status = 404; body = "{\"success\":false,\"error\":{\"NOT_FOUND\":\"" + path + "\"}}";
      }
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort() + "/cli/";
    client = new OneKeyClient(base, TOKEN, Duration.ofSeconds(5));
  }

  @AfterAll
  static void stop() { server.stop(0); }

  @Test
  void runtimeSecretsAreReadOnlyAndNeverPrinted() {
    RuntimeSecrets secrets = client.runtime("payments");
    assertEquals("sk_test", secrets.get("API_KEY").orElseThrow());
    assertTrue(secrets.has("DB-URL"));
    assertEquals(2, secrets.keys().size());
    assertFalse(secrets.toString().contains("sk_test"));
    assertThrows(UnsupportedOperationException.class, () -> secrets.entries().put("x", "y"));
    assertEquals("postgres://x", secrets.asEnv().get("DB-URL"));
  }

  @Test
  void secretAndNotFound() {
    assertEquals("sk_test", client.secret("payments", "API_KEY"));
    SecretNotFoundException e = assertThrows(SecretNotFoundException.class, () -> client.secret("payments", "NOPE"));
    assertEquals("SECRET_NOT_FOUND", e.code());
  }

  @Test
  void metadataEndpoints() {
    assertEquals("0.7.14", client.health().get("version"));
    assertEquals("payments", client.projects().get(0).name());
    assertEquals("production", client.groups("payments").get(0).name());
    assertEquals("Stripe", client.secretNames("payments").get(0).description());
    assertTrue(client.secretNames("payments", "other").isEmpty());
  }

  @Test
  void errorCodesFromEnvelope() {
    OneKeyException e = assertThrows(OneKeyException.class, () -> client.runtime("missing"));
    assertEquals(404, e.status());
    assertEquals("PROJECT_NOT_FOUND", e.code());
    assertEquals("AUTHENTICATION_INVALID", assertThrows(OneKeyException.class, () -> new OneKeyClient(base, "wrong").health()).code());
    assertEquals("INVALID_RESPONSE", assertThrows(OneKeyException.class, () -> OneKeyClient.unwrap(200, "not json")).code());
    assertEquals("NETWORK_ERROR", assertThrows(OneKeyException.class, () -> new OneKeyClient("http://127.0.0.1:9", "x", Duration.ofSeconds(2)).health()).code());
  }

  @Test
  void urlAndTokenRequired() {
    assertThrows(IllegalArgumentException.class, () -> new OneKeyClient("", "x"));
    assertThrows(IllegalArgumentException.class, () -> new OneKeyClient("http://x", " "));
  }
}
