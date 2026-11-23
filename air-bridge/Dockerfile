FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY canonical-model ./canonical-model
COPY air-bridge ./air-bridge
# 1) pom parent  2) modèle canonique  3) bridge
RUN mvn -q -B -N install -DskipTests \
 && mvn -q -B -f canonical-model/pom.xml install -DskipTests \
 && mvn -q -B -f air-bridge/pom.xml package -DskipTests

FROM eclipse-temurin:17-jre
ARG OTEL_AGENT_VERSION=2.10.0
RUN mkdir -p /opt/otel \
 && curl -fsSL "https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/${OTEL_AGENT_VERSION}/opentelemetry-javaagent-${OTEL_AGENT_VERSION}.jar" \
    -o /opt/otel/opentelemetry-javaagent.jar
WORKDIR /app
COPY --from=build /build/air-bridge/target/air-bridge.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]