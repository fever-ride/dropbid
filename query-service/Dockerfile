FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
RUN apk add --no-cache maven

COPY pom.xml .
COPY shared/pom.xml shared/pom.xml
COPY query-service/pom.xml query-service/pom.xml
RUN mvn dependency:go-offline -pl shared,query-service -am -q 2>/dev/null || true

COPY shared/src shared/src
COPY query-service/src query-service/src
RUN mvn -pl shared,query-service -am package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/query-service/target/*.jar app.jar
EXPOSE 8086
ENTRYPOINT ["java", "-Dspring.threads.virtual.enabled=true", "-jar", "app.jar"]
