# build context: repository root (so ../shared test fixtures are available to `mvn test`)
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY backend/mvn-retry.sh /usr/local/bin/mvnr
COPY backend/maven-mirror.xml /tmp/maven-mirror.xml
RUN chmod +x /usr/local/bin/mvnr
COPY backend/pom.xml backend/pom.xml
RUN mvnr -q -f backend/pom.xml dependency:go-offline
COPY shared shared
COPY backend/src backend/src
# unit tests run at image build time; a failing test fails the build
RUN mvnr -q -f backend/pom.xml package

FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /src/backend/target/intellitwin-backend-1.0.0.jar app.jar
USER app
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=12 CMD wget -qO- http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]