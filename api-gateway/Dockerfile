FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY . .
RUN mvn -B -ntp -pl api-gateway -am -DskipTests package

FROM eclipse-temurin:17-jre
ARG OTEL_AGENT_VERSION=2.10.0
RUN mkdir -p /opt/otel \
 && curl -fsSL "https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/${OTEL_AGENT_VERSION}/opentelemetry-javaagent-${OTEL_AGENT_VERSION}.jar" \
    -o /opt/otel/opentelemetry-javaagent.jar
WORKDIR /app
COPY --from=build /workspace/api-gateway/target/api-gateway.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
