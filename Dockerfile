FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
RUN useradd --system --uid 10001 --create-home --home-dir /app authgateway
WORKDIR /app

COPY --from=build /workspace/target/auth-gateway-0.0.1-SNAPSHOT.jar /app/auth-gateway.jar

ENV PORT=8080
EXPOSE 8080

USER authgateway
ENTRYPOINT ["java", "-jar", "/app/auth-gateway.jar"]
