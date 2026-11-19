# Backend runtime image. The jar is built first with Gradle, which needs Docker for jOOQ code generation
# (a throwaway PostGIS migrated with db/migration) and therefore cannot run inside a Docker build:
#   (cd backend && ./gradlew bootJar)
#   docker build -f backend/Dockerfile -t beavent-api .
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY backend/build/libs/beavent-api.jar ./beavent-api.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/beavent-api.jar"]
