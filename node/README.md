# onekey-sdk (Node.js)

```bash
npm install onekey-sdk
```

```js
import { OneKeyClient } from "onekey-sdk";

const client = new OneKeyClient({ url: "https://onekey.example.com/cli", token: "dbs_..." }); // or ONEKEY_URL / ONEKEY_TOKEN
const secrets = await client.runtime("payments");
console.log(secrets.keys());    // key names only
const value = secrets.get("DATABASE_URL");
```

Node 18+, ESM, no dependencies, TypeScript types included. See the [repository README](https://github.com/panp1/onekey-sdk) for the full API and error codes.
