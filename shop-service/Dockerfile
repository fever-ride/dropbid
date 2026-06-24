FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
RUN apk add --no-cache maven

COPY pom.xml .
COPY shared/pom.xml shared/pom.xml
COPY shop-service/pom.xml shop-service/pom.xml
COPY auction-service/pom.xml auction-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml
COPY payment-service/pom.xml payment-service/pom.xml
COPY user-service/pom.xml user-service/pom.xml
COPY query-service/pom.xml query-service/pom.xml
RUN mvn dependency:go-offline -pl shared,shop-service -am -q 2>/dev/null || true

COPY shared/src shared/src
COPY shop-service/src shop-service/src
RUN mvn -pl shared,shop-service -am package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/shop-service/target/*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-Dspring.threads.virtual.enabled=true", "-jar", "app.jar"]
