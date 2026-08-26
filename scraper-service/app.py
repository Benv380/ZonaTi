"""
API HTTP para el scraper de adjuntos de licitaciones (descargar_adjuntos.py).

Este es el UNICO servicio de todo el sistema que sale a internet a traves
del proxy residencial (ver SCRAPER_PROXY / ts-scraper-proxy en
docker-compose.yml) -- mercadopublico.cl bloquea la descarga de adjuntos
cuando la peticion sale desde un IP de datacenter (confirmado: el mismo
codigo funciona sin tocar nada desde un IP residencial). licitacion-service
(el servicio Java) le pide los adjuntos de una licitacion a esta API en vez
de correr Playwright el mismo -- asi licitacion-service ni siquiera necesita
Chromium instalado, y solo este contenedor puntual pasa por el tunel.

Uso (desde licitacion-service, no pensado para llamarse a mano):
    POST /adjuntos/{codigo_licitacion}
    -> 200 {"archivos": [{"nombre", "tipoContenido", "contenidoBase64"}, ...]}
    -> 502 si no se pudo descargar ningun archivo
"""

import base64
import tempfile
from pathlib import Path

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from descargar_adjuntos import download_all

app = FastAPI(
    title="scraper-service",
    description="Descarga adjuntos de licitaciones de Mercado Publico (scraping con Playwright).",
)

# Mismo mapeo extension -> MIME que antes vivia del lado Java -- ahora vive
# aca porque este servicio es el que conoce los archivos reales que bajo.
EXTENSION_MIME_TYPES = {
    "pdf": "application/pdf",
    "doc": "application/msword",
    "docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "xls": "application/vnd.ms-excel",
    "xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "zip": "application/zip",
    "rar": "application/vnd.rar",
    "rtf": "application/rtf",
    "kmz": "application/vnd.google-earth.kmz",
    "dwg": "image/vnd.dwg",
}


def adivinar_tipo_contenido(nombre_archivo: str) -> str:
    ext = nombre_archivo.rsplit(".", 1)[-1].lower() if "." in nombre_archivo else ""
    return EXTENSION_MIME_TYPES.get(ext, "application/octet-stream")


class ArchivoAdjunto(BaseModel):
    nombre: str
    tipoContenido: str
    contenidoBase64: str


class AdjuntosResponse(BaseModel):
    archivos: list[ArchivoAdjunto]


@app.post("/adjuntos/{codigo_licitacion}", response_model=AdjuntosResponse)
def obtener_adjuntos(codigo_licitacion: str) -> AdjuntosResponse:
    # Directorio temporal propio por request -- se borra siempre al salir
    # (el "with" lo garantiza incluso si download_all tira una excepcion).
    # El disco de este contenedor es solo un paso intermedio, nunca la
    # fuente de verdad: eso es la BD de licitacion-service.
    with tempfile.TemporaryDirectory(prefix=f"adjuntos_{codigo_licitacion}_") as tmp:
        out_dir = Path(tmp)
        try:
            archivos = download_all(codigo_licitacion, out_dir)
        except Exception as e:
            raise HTTPException(status_code=502, detail=f"Error descargando adjuntos: {e!r}") from e

        if not archivos:
            raise HTTPException(status_code=502, detail="No se pudo descargar ningún archivo.")

        return AdjuntosResponse(archivos=[
            ArchivoAdjunto(
                nombre=archivo.name,
                tipoContenido=adivinar_tipo_contenido(archivo.name),
                contenidoBase64=base64.b64encode(archivo.read_bytes()).decode("ascii"),
            )
            for archivo in archivos
        ])


@app.get("/health")
def health():
    """Chequeo simple: confirma que el proceso esta arriba, sin abrir
    ningun browser (eso solo pasa al pedir /adjuntos/{codigo})."""
    return {"status": "ok"}
