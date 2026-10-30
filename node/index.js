// OneKey SDK for Node.js (18+): fetch a project's secrets at runtime.
// No dependencies; uses the global fetch. Values are returned, never logged.

const VERSION = "0.1.2";
const API = "/api/v1";

export class OneKeyError extends Error {
  /**
   * @param {number} status HTTP status, 0 for transport failures
   * @param {string} code stable code from the envelope, e.g. PROJECT_NOT_FOUND
   * @param {string} message safe human-readable message
   */
  constructor(status, code, message) {
    super(`${code} (${status}): ${message}`);
    this.name = "OneKeyError";
    this.status = status;
    this.code = code;
    this.message = message;
  }
}

export class SecretNotFound extends OneKeyError {
  constructor(project, key) {
    super(404, "SECRET_NOT_FOUND", `${project} has no secret ${JSON.stringify(key)}`);
    this.name = "SecretNotFound";
    this.project = project;
    this.key = key;
  }
}

/** Every secret of a project (all groups). `entries` is a plain object of key → value. */
export class RuntimeSecrets {
  constructor({ project, environment = "", environmentId = "", entries = [] }) {
    this.project = project;
    this.environment = environment;
    this.environmentId = environmentId;
    this.entries = Object.fromEntries(entries.map((e) => [e.key, e.value]));
    Object.freeze(this.entries);
  }
  /** @returns {string | undefined} */
  get(key) {
    return this.entries[key];
  }
  has(key) {
    return Object.prototype.hasOwnProperty.call(this.entries, key);
  }
  keys() {
    return Object.keys(this.entries);
  }
  /** A copy for `spawn(cmd, { env: { ...process.env, ...secrets.asEnv() } })`. */
  asEnv() {
    return { ...this.entries };
  }
  // Keep values out of console.log / util.inspect output.
  [Symbol.for("nodejs.util.inspect.custom")]() {
    return `RuntimeSecrets { project: '${this.project}', keys: ${this.keys().length} }`;
  }
  toJSON() {
    return { project: this.project, keys: this.keys().length };
  }
}

export class OneKeyClient {
  /**
   * @param {{ url?: string, token?: string, timeoutMs?: number, fetch?: typeof fetch }} [options]
   * `url` is the client endpoint (e.g. https://onekey.example.com or https://onekey.example.com/cli behind
   * an SSO proxy); `token` a runner (dbs_) or personal (dpa_) token. Both default to ONEKEY_URL / ONEKEY_TOKEN.
   */
  constructor(options = {}) {
    const url = options.url ?? process.env.ONEKEY_URL;
    const token = options.token ?? process.env.ONEKEY_TOKEN;
    if (!url) throw new Error("OneKey url is required (option or ONEKEY_URL)");
    if (!token) throw new Error("OneKey token is required (option or ONEKEY_TOKEN)");
    this.base = url.replace(/\/+$/, "");
    this.timeoutMs = options.timeoutMs ?? 10_000;
    this._token = token;
    this._fetch = options.fetch ?? globalThis.fetch;
  }

  /** Server identity: product, version, apiVersion, status. */
  health() {
    return this._get("/health");
  }

  /** Projects the token can see. Needs a personal token; runner tokens get AUTHORIZATION_DENIED. */
  async projects() {
    const rows = await this._get("/projects");
    return rows.map((p) => ({ id: p.id, name: p.name, createdAt: p.createdAt, updatedAt: p.updatedAt, myRole: p.myRole ?? null }));
  }

  /** Groups of a project. Groups organise secrets; they carry no permissions. */
  async groups(project) {
    const rows = await this._get("/environments", { project });
    return rows.map((g) => ({ id: g.id, projectId: g.projectId, projectName: g.projectName, name: g.name, createdAt: g.createdAt, updatedAt: g.updatedAt }));
  }

  /** Key names and metadata (never values) of a project, optionally one group (name or id). */
  async secretNames(project, group) {
    const result = [];
    for (const g of await this.groups(project)) {
      if (group !== undefined && g.name !== group && g.id !== group) continue;
      for (const s of await this._get(`/environments/${encodeURIComponent(g.id)}/secrets`)) {
        result.push({ key: s.key, version: s.version, createdAt: s.createdAt, updatedAt: s.updatedAt, description: s.description ?? null });
      }
    }
    return result;
  }

  /** Every secret value of the project, across all its groups. */
  async runtime(project) {
    return new RuntimeSecrets(await this._get(`/projects/${encodeURIComponent(project)}/secrets/runtime`));
  }

  /** One secret value; rejects with SecretNotFound when the key does not exist. */
  async secret(project, key) {
    const secrets = await this.runtime(project);
    if (!secrets.has(key)) throw new SecretNotFound(project, key);
    return secrets.get(key);
  }

  async _get(path, query) {
    let url = `${this.base}${API}${path}`;
    if (query) url += `?${new URLSearchParams(query)}`;
    let response;
    try {
      response = await this._fetch(url, {
        headers: { Authorization: `Bearer ${this._token}`, Accept: "application/json", "User-Agent": `onekey-sdk-node/${VERSION}` },
        signal: AbortSignal.timeout(this.timeoutMs),
      });
    } catch (error) {
      throw new OneKeyError(0, error?.name === "TimeoutError" ? "TIMEOUT" : "NETWORK_ERROR", error?.message ?? String(error));
    }
    const text = await response.text();
    let envelope;
    try {
      envelope = JSON.parse(text);
    } catch {
      throw new OneKeyError(response.status, response.ok ? "INVALID_RESPONSE" : "HTTP_ERROR", `OneKey responded with HTTP ${response.status}`);
    }
    if (!response.ok) {
      const [code, message] = Object.entries(envelope?.error ?? {})[0] ?? ["HTTP_ERROR", `OneKey responded with HTTP ${response.status}`];
      throw new OneKeyError(response.status, String(code), String(message));
    }
    if (envelope?.success !== true) {
      throw new OneKeyError(response.status, "INVALID_RESPONSE", "OneKey returned an unexpected body");
    }
    return envelope.data;
  }
}
