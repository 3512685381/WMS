# WMS
智能仓库管理系统

## Spring Boot 后端

构建和运行后端需要 JDK 21（LTS）。请将 `JAVA_HOME` 指向 JDK 21，并用 `mvn -v` 确认 Maven 使用该版本。

在后端目录运行测试和启动应用，以便读取该目录下的 `.env` 配置：

```sh
cd springboot
mvn clean test
mvn spring-boot:run
```
