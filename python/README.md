# onekey-sdk (Python)

```bash
pip install onekey-sdk
```

```python
from onekey_sdk import OneKeyClient, OneKeyError

client = OneKeyClient(url="https://onekey.example.com/cli", token="dbs_...")  # or ONEKEY_URL / ONEKEY_TOKEN
secrets = client.runtime("payments")
print(sorted(secrets))          # key names only
value = secrets["DATABASE_URL"]
```

Python 3.9+, standard library only. See the [repository README](https://github.com/panp1/onekey-sdk) for the full API and error codes.
