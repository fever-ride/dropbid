FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
RUN apk add --no-cache maven

COPY pom.xml .
COPY shared/pom.xml shared/pom.xml
COPY bid-service/pom.xml bid-service/pom.xml
RUN mvn dependency:go-offline -pl shared,bid-service -am -q 2>/dev/null || true

COPY shared/src shared/src
COPY bid-service/src bid-service/src
RUN mvn -pl shared,bid-service -am package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/bid-service/target/*.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-Dspring.threads.virtual.enabled=true", "-jar", "app.jar"]
