# Build from project root: docker build -f user-service/Dockerfile .
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
RUN apk add --no-cache maven

COPY pom.xml .
COPY shared/pom.xml shared/pom.xml
COPY user-service/pom.xml user-service/pom.xml
# Resolve dependencies first (better layer caching)
RUN mvn dependency:go-offline -pl shared,user-service -am -q 2>/dev/null || true

COPY shared/src shared/src
COPY user-service/src user-service/src
RUN mvn -pl shared,user-service -am package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/user-service/target/*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-Dspring.threads.virtual.enabled=true", "-jar", "app.jar"]
