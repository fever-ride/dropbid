FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
RUN apk add --no-cache maven

COPY pom.xml .
COPY shared/pom.xml shared/pom.xml
COPY auction-service/pom.xml auction-service/pom.xml
RUN mvn dependency:go-offline -pl shared,auction-service -am -q 2>/dev/null || true

COPY shared/src shared/src
COPY auction-service/src auction-service/src
RUN mvn -pl shared,auction-service -am package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/auction-service/target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-Dspring.threads.virtual.enabled=true", "-jar", "app.jar"]
