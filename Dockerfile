# =====================================================================
#  Multi-stage build: stage 1 compiles with Maven + JDK, stage 2 only
#  contains a JRE and the jar. The final image is much smaller and holds
#  no source code or build tools.
# =====================================================================

# ---------- build stage ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
# Copy pom.xml alone first and download dependencies: Docker caches this
# layer, so changing a .java file does not re-download the whole internet.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
# Tests need Docker (Testcontainers), unavailable inside a Docker build:
# run them on the host with `mvn verify`.
RUN mvn -B -q package -DskipTests

# ---------- runtime stage ----------
FROM eclipse-temurin:21-jre
# Never run as root inside the container: limits damage if the app is compromised.
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build /workspace/target/ewallet-api-*.jar app.jar
USER app
EXPOSE 8080
# MaxRAMPercentage: size the heap from the container's memory limit, not the host's.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
