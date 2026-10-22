FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# The poms first: the dependency layer is rebuilt only when a pom changes, not on every source edit.
COPY pom.xml .
COPY concessions-core/pom.xml concessions-core/
COPY concessions-adapters/pom.xml concessions-adapters/
COPY concessions-app/pom.xml concessions-app/
RUN mvn -q -B dependency:go-offline
COPY concessions-core/src concessions-core/src
COPY concessions-adapters/src concessions-adapters/src
COPY concessions-app/src concessions-app/src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre
COPY --from=build /src/concessions-app/target/concessions-app-0.1.0.jar /app.jar
USER 10001
ENTRYPOINT ["java", "-jar", "/app.jar"]
