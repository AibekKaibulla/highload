# syntax=docker/dockerfile:1.7
# One Dockerfile for all services: docker build --build-arg MODULE=vote-service .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
ARG MODULE
# Maven cache is shared between builds; "locked" so parallel builds don't corrupt it.
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -q -B -pl ${MODULE} -am package -DskipTests

FROM eclipse-temurin:21-jre
ARG MODULE
WORKDIR /app
COPY --from=build /src/${MODULE}/target/${MODULE}.jar app.jar
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
