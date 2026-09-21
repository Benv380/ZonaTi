FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Solo Compra Agil (proxy sobre la API de Mercado Publico) -- sin Python/
# Chromium/Xvfb, ese scraping vive aparte en scraper-service desde el split
# de compra-service en 2 servicios (Compra Agil / Licitacion). Alpine
# alcanza porque acá no hay ninguna dependencia que necesite glibc.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
