FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

COPY gradlew settings.gradle build.gradle lombok.config ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies --quiet || true


COPY src ./src
RUN ./gradlew --no-daemon bootJar -x test


FROM eclipse-temurin:21-jre


RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 app

WORKDIR /app
COPY --from=build /app/build/libs/*-SNAPSHOT.jar app.jar
USER app


EXPOSE 8080


ENTRYPOINT ["java", "-jar", "app.jar"]