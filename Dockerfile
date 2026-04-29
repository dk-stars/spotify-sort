# syntax=docker/dockerfile:1.7

FROM maven:3-eclipse-temurin-17-alpine AS build
WORKDIR /workspace
COPY pom.xml .
COPY mvnw .
COPY .mvn .mvn
COPY src src
RUN ./mvnw -B -DskipTests package

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN useradd --system --create-home --uid 10001 app
COPY --from=build /workspace/target/spotify-sort-0.0.1-SNAPSHOT.jar /app/app.jar
ENV SERVER_PORT=8080
EXPOSE 8080
USER app
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
