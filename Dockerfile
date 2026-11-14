# syntax=docker/dockerfile:1

# ---- build stage: compile and package the Spring Boot jar ----
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
# Tests need Docker (Testcontainers), so they run in CI with `mvn verify`, not inside this build.
RUN mvn -B -ntp -DskipTests package

# ---- runtime stage: JRE only, non-root user ----
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 app
COPY --from=build /src/target/concurrent-seat-booking-engine-*.jar /app/app.jar
USER app
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Duser.timezone=UTC"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
