# syntax=docker/dockerfile:1.7

FROM maven:3.9.11-eclipse-temurin-24 AS build
WORKDIR /workspace

COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package

FROM eclipse-temurin:24-jre
WORKDIR /app

ENV TZ=Asia/Shanghai

COPY --from=build /workspace/target/dealtrace-backend-0.0.1-SNAPSHOT.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
