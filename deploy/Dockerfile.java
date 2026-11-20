FROM registry-1.docker.io/library/eclipse-temurin:21-jdk-jammy@sha256:432e54724c3e57d1846aeb886f4d339f3b0522931c4e64119c2797013b7cfc3c AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY testflow-core testflow-core
COPY testflow-persistence testflow-persistence
COPY testflow-api testflow-api
COPY testflow-worker testflow-worker
RUN --mount=type=cache,target=/root/.m2 sh ./mvnw -B -ntp -DskipTests package

FROM registry-1.docker.io/library/eclipse-temurin:21-jre-jammy@sha256:dff6eb46527bd293a3c06bbbe9e64e8c9f91f01905b5abbdad31761c03059d2c AS runtime
ARG APP=api
WORKDIR /app
COPY --from=build --chown=10001:10001 /build/testflow-${APP}/target/testflow-${APP}-0.1.0-SNAPSHOT.jar /app/application.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
