# syntax=docker/dockerfile:1
FROM eclipse-temurin:26.0.2.1_1-jdk-noble AS build
ENV MAVEN_OPTS="-Xmx384m -XX:ActiveProcessorCount=2"
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp package -DskipTests

FROM eclipse-temurin:26.0.2.1_1-jre-noble
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/target/quarkus-app/ ./
USER 10001:10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:InitialRAMPercentage=15 -XX:ActiveProcessorCount=2"
ENTRYPOINT ["java", "-jar", "/app/quarkus-run.jar"]
