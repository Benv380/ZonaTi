FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Compra Agil no necesita Chromium/Playwright (su unica descarga de
# binarios es via AdjuntoClient, HTTP directo con headers de navegador --
# ver comentario en AdjuntoClient) -- alcanza con un JRE simple, mas liviano
# que la imagen de licitacion-service.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
