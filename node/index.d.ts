export interface Project { id: string; name: string; createdAt: string; updatedAt: string; myRole: string | null }
export interface Group { id: string; projectId: string; projectName: string; name: string; createdAt: string; updatedAt: string }
export interface SecretMetadata { key: string; version: number; createdAt: string; updatedAt: string; description: string | null }
export interface Health { product: string; version: string; apiVersion: string; status: string; database?: string }

export class OneKeyError extends Error {
  status: number;
  code: string;
  constructor(status: number, code: string, message: string);
}
export class SecretNotFound extends OneKeyError {
  project: string;
  key: string;
}
export class RuntimeSecrets {
  readonly project: string;
  readonly environment: string;
  readonly environmentId: string;
  readonly entries: Readonly<Record<string, string>>;
  get(key: string): string | undefined;
  has(key: string): boolean;
  keys(): string[];
  asEnv(): Record<string, string>;
}
export interface OneKeyClientOptions {
  /** Client endpoint, e.g. https://onekey.example.com or https://onekey.example.com/cli. Defaults to ONEKEY_URL. */
  url?: string;
  /** Runner (dbs_) or personal (dpa_) token. Defaults to ONEKEY_TOKEN. */
  token?: string;
  timeoutMs?: number;
  fetch?: typeof fetch;
}
export class OneKeyClient {
  constructor(options?: OneKeyClientOptions);
  health(): Promise<Health>;
  projects(): Promise<Project[]>;
  groups(project: string): Promise<Group[]>;
  secretNames(project: string, group?: string): Promise<SecretMetadata[]>;
  runtime(project: string): Promise<RuntimeSecrets>;
  secret(project: string, key: string): Promise<string>;
}
