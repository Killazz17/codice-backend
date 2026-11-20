# Stage 1: Build
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app
COPY gradle/ gradle/
COPY gradlew gradlew.bat build.gradle.kts settings.gradle.kts ./
# El working copy en Windows puede traer gradlew con CRLF (core.autocrlf); Alpine no lo ejecutaría así.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
COPY src/ src/
RUN ./gradlew bootJar --no-daemon -x test

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S codice && adduser -S codice -G codice
COPY --from=build --chown=codice:codice /app/build/libs/*.jar app.jar
USER codice
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java -XX:MaxRAMPercentage=70.0 -Dserver.port=${PORT:-8080} -jar app.jar"]
