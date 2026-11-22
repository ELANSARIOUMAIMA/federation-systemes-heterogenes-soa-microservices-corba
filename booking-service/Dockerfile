FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY . .
RUN mvn -B -ntp -pl booking-service -am -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/booking-service/target/booking-service.jar app.jar
VOLUME ["/data"]
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
