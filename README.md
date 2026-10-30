# OneKey SDK

Official client libraries for reading [OneKey](https://github.com/panp1/onekey) secrets from your application at runtime. One API, three languages:

| Language | Package | Requires |
| --- | --- | --- |
| Python | `pip install onekey-sdk` ([python/](python/)) | Python 3.9+, no dependencies |
| Node.js | `npm install onekey-sdk` ([node/](node/)) | Node 18+, no dependencies |
| Java | JitPack `com.github.panp1.onekey-sdk:onekey-sdk:0.1.0` ([java/](java/)) | Java 17+, Jackson |

The SDKs are read-side by design: they fetch values for the process that needs them and list metadata. Creating or changing secrets stays in the console and the `onekey` CLI.

## Before you start

1. Ask your OneKey admin for a **runner token** of your project (Project → Tokens → New token). A runner token reads every group of its own project and nothing else; it cannot reveal, export or change secrets.
2. Note the **client endpoint**. Behind an SSO proxy it ends in `/cli`, for example `https://onekey.example.com/cli`; a direct deployment is just `https://onekey.example.com`.
3. Put both in the environment of your service:

```bash
export ONEKEY_URL=https://onekey.example.com/cli
export ONEKEY_TOKEN=dbs_...
```

Never commit the token. Rotate it from the console if it leaks.

## Python

```python
from onekey_sdk import OneKeyClient

client = OneKeyClient()                      # reads ONEKEY_URL / ONEKEY_TOKEN
secrets = client.runtime("payments")         # every group of the project
db_url = secrets["DATABASE_URL"]
stripe = client.secret("payments", "STRIPE_API_KEY")

import subprocess, os
subprocess.run(["./migrate"], env={**os.environ, **secrets.as_env()})
```

## Node.js

```js
import { OneKeyClient } from "onekey-sdk";

const client = new OneKeyClient();           // reads ONEKEY_URL / ONEKEY_TOKEN
const secrets = await client.runtime("payments");
const dbUrl = secrets.get("DATABASE_URL");
const stripe = await client.secret("payments", "STRIPE_API_KEY");
```

## Java

```java
import com.onekey.sdk.OneKeyClient;
import com.onekey.sdk.RuntimeSecrets;

OneKeyClient client = OneKeyClient.fromEnv();   // ONEKEY_URL / ONEKEY_TOKEN
RuntimeSecrets secrets = client.runtime("payments");
String dbUrl = secrets.get("DATABASE_URL").orElseThrow();
String stripe = client.secret("payments", "STRIPE_API_KEY");
```

## API

Every language exposes the same calls:

| Call | Returns | Needs |
| --- | --- | --- |
| `health()` | product, version, apiVersion, status | any token |
| `projects()` | projects the token can see | any token |
| `groups(project)` | groups of the project | member or runner token |
| `secretNames(project[, group])` | key, version, description, timestamps (no values) | member or runner token |
| `runtime(project)` | every key and value of the project | runner token, or personal token with value access |
| `secret(project, key)` | one value; `SecretNotFound` when the key is missing | same as `runtime` |

Errors carry `status` (HTTP status, `0` for network failures) and `code`, the stable OneKey error code. Branch on the code:

| Code | Meaning |
| --- | --- |
| `AUTHENTICATION_INVALID` | token missing, revoked or expired |
| `AUTHORIZATION_DENIED` | token cannot read values (value access off) or role too low |
| `TOKEN_SCOPE_INVALID` | token scope excludes the project |
| `PROJECT_NOT_FOUND` | wrong project name, or not a member |
| `NETWORK_ERROR` / `TIMEOUT` | server unreachable |

## Guidance

- Fetch once at startup and keep the values in memory; `runtime()` is one request per call and there is no client-side cache. Re-fetch when you rotate a secret.
- Keys follow `[A-Za-z_][A-Za-z0-9_-]*`. A key with `-` cannot be read as `$NAME` by a shell; read it from your application's environment lookup instead.
- Secret containers never include values in `repr`, `inspect` or `toString`; still, keep them out of logs.
- Timeouts default to 10 seconds; pass `timeout` / `timeoutMs` / `Duration` to change them.

## Development

```bash
cd python && PYTHONPATH=src python -m unittest discover -s tests
cd node && node --test
cd java && mvn -B test
```

Each test suite runs against an in-process stub of the OneKey API. Licensed under Apache-2.0.
