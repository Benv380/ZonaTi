# Servicio Python puro (FastAPI + Playwright) -- es el UNICO contenedor de
# todo el sistema que necesita Chromium, y el UNICO que sale por el tunel de
# Tailscale (ver SCRAPER_PROXY en docker-compose.yml). licitacion-service
# (Java) le pide los adjuntos por HTTP a este servicio, no corre Playwright
# el mismo.
FROM python:3.12-slim
WORKDIR /app

# xvfb: Chromium necesita una pantalla real para dibujarse -- headless=True
# dispara el bloqueo anti-bot del portal (ver descargar_adjuntos.py), asi
# que en un contenedor sin escritorio hace falta una pantalla virtual.
# tini: PID 1 real, recolecta (reap) procesos zombie que van quedando de
# Chromium -- sin esto se acumulan hasta agotar los procesos del sistema.
RUN apt-get update && apt-get install -y --no-install-recommends \
    xvfb \
    tini \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
# "--with-deps" baja el binario de Chromium Y las librerias de sistema que
# necesita (libnss, fuentes, etc.) en un solo paso.
RUN pip install --no-cache-dir -r requirements.txt \
    && playwright install --with-deps chromium

COPY app.py descargar_adjuntos.py ./

EXPOSE 8090
# xvfb-run envuelve el proceso de uvicorn ENTERO (a diferencia del uso viejo
# desde Java, que envolvia cada invocacion suelta del script) -- la pantalla
# virtual queda disponible toda la vida del contenedor, no solo por request.
# tini como PID 1 real por debajo, para el reaping de zombies.
ENTRYPOINT ["/usr/bin/tini", "--", "xvfb-run", "-a", "--server-args=-screen 0 1920x1080x24", \
            "python3", "-m", "uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8090"]
