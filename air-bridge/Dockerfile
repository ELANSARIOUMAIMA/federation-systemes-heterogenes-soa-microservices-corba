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
WORKDIR /app
COPY --from=build /build/air-bridge/target/air-bridge.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]