# Backend image. Build from the repository root (the schema in db/migration is packaged for Flyway):
#   docker build -f backend/Dockerfile -t beavent-api .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src/backend
COPY backend/gradlew backend/build.gradle backend/settings.gradle ./
COPY backend/gradle ./gradle
RUN ./gradlew --no-daemon --quiet dependencies > /dev/null
COPY backend/src ./src
COPY db/migration /src/db/migration
RUN ./gradlew --no-daemon --quiet bootJar -x test

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /src/backend/build/libs/beavent-api.jar ./beavent-api.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/beavent-api.jar"]
