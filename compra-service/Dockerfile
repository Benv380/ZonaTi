FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# --- Cambio de base: Alpine -> Ubuntu (jammy) ---
# Compra Agil (proxy sobre la API de Mercado Publico) seguia sin necesitar
# Python/Chromium -- ese scraping vivia aparte en scraper-service, para
# licitacion-service. Pero ahora este servicio TAMBIEN corre un script
# Playwright propio (obtener_token_reclamos.py) para renovar solo el token
# de comprador-api-pro.mercadopublico.cl (ver ReclamosTokenScheduler) --
# Playwright NO soporta Alpine/musl (los binarios de Chromium que descarga
# son para glibc), asi que pasa a Ubuntu igual que licitacion-service/
# scraper-service tuvieron que hacer antes.
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# python3/pip: para correr scripts/obtener_token_reclamos.py.
# xvfb: Chromium corre con interfaz grafica real (no headless) -- el mismo
# criterio que ya se uso para licitacion-service/scraper-service, aunque
# acá no esta confirmado que este dominio bloquee headless (se dejo el
# mismo patron por las dudas, ver el script). Cada invocacion se envuelve
# con "xvfb-run -a" por separado (ver application-linux.yml), no hace
# falta levantar Xvfb a mano en este Dockerfile.
# tini: PID 1 real para recolectar (reap) procesos zombie de
# xvfb-run/Chromium -- sin esto se acumulan hasta agotar los procesos del
# sistema operativo.
RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 \
    python3-pip \
    xvfb \
    tini \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/target/*.jar app.jar
COPY scripts ./scripts

# "--with-deps" baja el binario de Chromium Y las librerías de sistema que
# necesita (libnss, fuentes, etc.) en un solo paso.
RUN pip3 install --no-cache-dir -r scripts/requirements.txt \
    && python3 -m playwright install --with-deps chromium

# Esta imagen es siempre Linux headless -> activa el perfil que antepone
# xvfb-run a la invocación del script Python (ver application-linux.yml).
ENV SPRING_PROFILES_ACTIVE=linux

EXPOSE 8082
ENTRYPOINT ["/usr/bin/tini", "--", "java", "-jar", "app.jar"]
