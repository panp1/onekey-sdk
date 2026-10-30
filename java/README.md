# onekey-sdk (Java)

Via [JitPack](https://jitpack.io):

```xml
<repositories>
  <repository><id>jitpack.io</id><url>https://jitpack.io</url></repository>
</repositories>
<dependency>
  <groupId>com.github.panp1.onekey-sdk</groupId>
  <artifactId>onekey-sdk</artifactId>
  <version>0.1.2</version>
</dependency>
```

```java
import com.onekey.sdk.OneKeyClient;
import com.onekey.sdk.RuntimeSecrets;

OneKeyClient client = new OneKeyClient("https://onekey.example.com/cli", "dbs_..."); // or OneKeyClient.fromEnv()
RuntimeSecrets secrets = client.runtime("payments");
System.out.println(secrets.keys());        // key names only
String value = secrets.get("DATABASE_URL").orElseThrow();
```

Java 17+, depends on Jackson only. See the [repository README](https://github.com/panp1/onekey-sdk) for the full API and error codes.
