# Multi-stage build for the Spring Boot backend.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
# Download dependencies first for better layer caching.
RUN ./mvnw -B -o dependency:go-offline || ./mvnw -B dependency:go-offline
COPY src src
RUN ./mvnw -B -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
# Render provides PORT; default to 8080 for local runs.
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java -jar /app/app.jar --server.port=${PORT}"]
