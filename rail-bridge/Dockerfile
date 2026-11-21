# Build en JDK 8 (compilateur IDL JacORB), exécution en JRE 8 : JacORB 3.9 ne tourne pas au-delà.
FROM maven:3.9-eclipse-temurin-8 AS build
WORKDIR /build
COPY pom.xml .
COPY canonical-model ./canonical-model
COPY rail-bridge ./rail-bridge
RUN mvn -q -B -N install -DskipTests \
 && mvn -q -B -f canonical-model/pom.xml install -DskipTests \
 && mvn -q -B -f rail-bridge/pom.xml package -DskipTests

FROM eclipse-temurin:8-jre
WORKDIR /app
COPY --from=build /build/rail-bridge/target/rail-bridge.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]