# syntax=docker/dockerfile:1
# Self-contained public demo: the booking API and PostgreSQL 16 in ONE container, sized for a 512 MB free instance.
# Data lives inside the container and is lost on every restart (free instances sleep), which is what a demo wants.
# Kafka and the notification service are not part of the demo (BOOKING_OUTBOX_ENABLED=false); see docker-compose.yml.
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-noble
RUN apt-get update && apt-get install -y --no-install-recommends postgresql-16 && rm -rf /var/lib/apt/lists/*
RUN useradd --system --create-home --uid 10001 app
WORKDIR /app
COPY --from=build /src/target/concurrent-seat-booking-engine-*.jar /app/app.jar
COPY deploy/demo/start.sh /app/start.sh
RUN chmod +x /app/start.sh
USER app
ENV PGDATA=/home/app/pgdata \
    DB_URL=jdbc:postgresql://127.0.0.1:5432/seatbooking DB_USERNAME=app DB_PASSWORD=unused-local-trust DB_POOL_SIZE=8 \
    BOOKING_OUTBOX_ENABLED=false BOOKING_DEMO_ENABLED=true BOOKING_DEMO_MAX_SHOWS=50 \
    MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info \
    JAVA_OPTS="-Xmx256m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC -Xss512k -Duser.timezone=UTC"
EXPOSE 8080
CMD ["/app/start.sh"]
