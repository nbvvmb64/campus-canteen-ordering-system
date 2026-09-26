# Optional public-demo container sources

This directory shows the isolated MySQL demo schema and the HTTPS-proxy boundary used by the source project. It does not contain a JAR or any secret.

Before building the web image, build the application and copy the generated artifact locally:

```powershell
mvn -f .\java\web\pom.xml -DskipTests package
Copy-Item .\java\web\target\canteen-web-1.0.0.jar .\java\web\demo-deploy\web\
```

The copied JAR is ignored by Git. Supply all database passwords, the merchant seed password, and the proxy secret through the deployment platform's secret store. The provided proxy entrypoint is designed for a private Railway database transport and HTTPS edge proxy; review the environment contract before adapting it to another platform.
