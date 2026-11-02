# syntax=docker/dockerfile:1.7
# Ubuntu, ne Alpine: Node za esbuild (web /app) je napravljen za glibc.
FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml package.json package-lock.json ./
COPY src src
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -q -DskipTests package


FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S app && adduser -S app -G app

WORKDIR /app
COPY --from=build /workspace/target/backend-0.0.1-SNAPSHOT.jar app.jar

USER app
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
