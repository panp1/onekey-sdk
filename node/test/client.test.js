import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { inspect } from "node:util";
import { OneKeyClient, OneKeyError, SecretNotFound } from "../index.js";

const TOKEN = "dbs_test_token";
const routes = {
  "/cli/api/v1/health": { product: "onekey", version: "0.7.14", apiVersion: "v1", status: "ok" },
  "/cli/api/v1/projects": [{ id: "prj_1", name: "payments", createdAt: "t", updatedAt: "t", myRole: "developer" }],
  "/cli/api/v1/environments?project=payments": [{ id: "env_1", projectId: "prj_1", projectName: "payments", name: "production", createdAt: "t", updatedAt: "t" }],
  "/cli/api/v1/environments/env_1/secrets": [{ key: "API_KEY", version: 2, createdAt: "t", updatedAt: "t", description: "Stripe" }],
  "/cli/api/v1/projects/payments/secrets/runtime": {
    project: "payments", environment: "production", environmentId: "env_1",
    entries: [{ key: "API_KEY", value: "sk_test" }, { key: "DB-URL", value: "postgres://x" }],
  },
};
let server, client, base;
before(async () => {
  server = createServer((req, res) => {
    const send = (status, body) => { res.writeHead(status, { "Content-Type": "application/json" }); res.end(JSON.stringify(body)); };
    if (req.headers.authorization !== `Bearer ${TOKEN}`) return send(401, { success: false, error: { AUTHENTICATION_INVALID: "Sign in." } });
    if (req.url === "/cli/api/v1/projects/missing/secrets/runtime") return send(404, { success: false, error: { PROJECT_NOT_FOUND: "No such project." } });
    if (req.url === "/cli/api/v1/broken") { res.writeHead(200, { "Content-Type": "text/html" }); return res.end("<html>not json</html>"); }
    if (req.url in routes) return send(200, { success: true, message: "OK", data: routes[req.url] });
    send(404, { success: false, error: { NOT_FOUND: req.url } });
  });
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  base = `http://127.0.0.1:${server.address().port}/cli/`;
  client = new OneKeyClient({ url: base, token: TOKEN });
});
after(() => server.close());

test("runtime secrets are a frozen map that never prints values", async () => {
  const secrets = await client.runtime("payments");
  assert.equal(secrets.get("API_KEY"), "sk_test");
  assert.equal(secrets.has("DB-URL"), true);
  assert.deepEqual(secrets.keys().sort(), ["API_KEY", "DB-URL"]);
  assert.ok(!inspect(secrets).includes("sk_test"));
  assert.ok(!JSON.stringify(secrets).includes("sk_test"));
  assert.equal(secrets.asEnv()["DB-URL"], "postgres://x");
  assert.throws(() => { secrets.entries.API_KEY = "x"; });
});

test("secret() and SecretNotFound", async () => {
  assert.equal(await client.secret("payments", "API_KEY"), "sk_test");
  await assert.rejects(client.secret("payments", "NOPE"), (e) => e instanceof SecretNotFound && e.code === "SECRET_NOT_FOUND");
});

test("metadata endpoints", async () => {
  assert.equal((await client.health()).version, "0.7.14");
  assert.equal((await client.projects())[0].name, "payments");
  assert.equal((await client.groups("payments"))[0].name, "production");
  const names = await client.secretNames("payments");
  assert.equal(names[0].key, "API_KEY");
  assert.equal(names[0].description, "Stripe");
  assert.deepEqual(await client.secretNames("payments", "other"), []);
});

test("error codes from the envelope", async () => {
  await assert.rejects(client.runtime("missing"), (e) => e instanceof OneKeyError && e.status === 404 && e.code === "PROJECT_NOT_FOUND");
  await assert.rejects(new OneKeyClient({ url: base, token: "wrong" }).health(), (e) => e.code === "AUTHENTICATION_INVALID");
  await assert.rejects(client._get("/broken"), (e) => e.code === "INVALID_RESPONSE");
  await assert.rejects(new OneKeyClient({ url: "http://127.0.0.1:9", token: "x", timeoutMs: 2000 }).health(), (e) => e.code === "NETWORK_ERROR");
});

test("url and token are required", () => {
  assert.throws(() => new OneKeyClient({ url: "", token: "x" }));
  assert.throws(() => new OneKeyClient({ url: "http://x", token: "" }));
});
