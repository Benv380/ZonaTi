"""
Descarga los archivos adjuntos (bases + anexos) de una licitación pública
de Mercado Público (mercadopublico.cl), replicando el mismo tipo de acceso
que usa Licify: scraping de la ficha web, no una API oficial (no existe
endpoint de adjuntos en api.mercadopublico.cl).

Requisitos:
    pip install playwright
    playwright install chromium

Uso:
    python3 descargar_adjuntos.py 903031-10-L126
    python3 descargar_adjuntos.py 903031-10-L126 --out ./descargas

Notas:
- Esto se corre CONTRA EL SITIO REAL, así que necesita salir a internet sin
  restricciones (no funciona dentro de un sandbox con red en lista blanca).
- No requiere login: la ficha de una licitación pública es de acceso libre.
- Los selectores (__doPostBack, textos de botones) pueden variar según el
  tipo de proceso (Licitación Pública vs Convenio Marco vs Compra Ágil) o
  si ChileCompra actualiza el HTML del portal.
"""

import argparse
import os
import re
import sys
from pathlib import Path

from playwright.sync_api import sync_playwright, TimeoutError as PWTimeout

BASE_URL = "https://www.mercadopublico.cl/Procurement/Modules/RFB/DetailsAcquisition.aspx?idlicitacion={}"

# Palabras clave que suelen aparecer en los controles de descarga de la ficha
DOWNLOAD_KEYWORDS = ["descarg", "bases", "anexo", "adjunt", "documento", "archivo"]


def find_attachment_page_link(page):
    """Busca en la ficha principal ÚNICAMENTE el control que abre la
    página de anexos. En la práctica es un <input type="image"
    id="imgAdjuntos"> cuyo onclick hace window.open(...) a
    ViewAttachment.aspx en una ventana POPUP nueva (no navega la misma
    pestaña). Esta es la ÚNICA búsqueda que se hace en la ficha."""
    el = page.query_selector("#imgAdjuntos")
    if el:
        return el
    # fallback por si cambia el id: buscamos por el onclick
    for el in page.query_selector_all("input[type=image], a"):
        onclick = el.get_attribute("onclick") or ""
        if "ViewAttachment" in onclick:
            return el
    return None


def find_bases_button(page):
    """Busca en la ficha principal ÚNICAMENTE el botón de 'Descarga
    bases'. Se busca aparte de la lista genérica de candidatos a
    propósito, para no tener que barrer toda la ficha con keywords."""
    return page.query_selector("#descargar_pdf_baseFirmada")


def find_download_candidates(page, skip_ids=()):
    """Busca en la página todos los elementos clicleables que parecen
    disparar una descarga de archivo (link, button, input) y que además
    tienen un __doPostBack o un href a un handler de descarga.

    skip_ids: ids de controles a excluir a propósito (por ej. el botón
    que abre el popup de anexos, que ya procesamos aparte y NO queremos
    re-clickear como si fuera una descarga)."""
    candidates = []
    elements = page.query_selector_all(
        "a, input[type=submit], input[type=button], input[type=image], button"
    )

    for el in elements:
        try:
            text = (el.inner_text() or el.get_attribute("value") or "").strip()
            onclick = el.get_attribute("onclick") or ""
            href = el.get_attribute("href") or ""
            elid = el.get_attribute("id") or ""
            title = el.get_attribute("title") or ""
        except Exception:
            continue

        if elid in skip_ids:
            continue

        haystack = f"{text} {onclick} {href} {elid} {title}".lower()
        if any(k in haystack for k in DOWNLOAD_KEYWORDS):
            candidates.append({"el": el, "text": text or title, "onclick": onclick, "href": href, "id": elid})

    return candidates


FILE_EXTENSIONS = (".pdf", ".doc", ".docx", ".xls", ".xlsx", ".zip", ".rar", ".dwg", ".rtf")


def try_click_and_get_file(context, scan_page, el, label: str, out_dir: Path, licitacion_id: str):
    """Clickea un control candidato y trata de resolver el archivo
    resultante, cubriendo los 3 casos que aparecen en mercadopublico.cl:
      1) dispara un evento de descarga normal del navegador
      2) abre una ventana/pestaña nueva con el PDF (Chrome lo muestra
         inline en vez de "descargarlo", así que lo bajamos por HTTP
         directo usando la misma sesión/cookies del browser)
      3) hace un postback ASP.NET que deja el archivo en un <iframe> dentro
         de la MISMA página (confirmado: así funciona "Ver Anexo" en el
         listado de anexos de una licitación normal)
    """
    # snapshot de páginas ANTES de clickear, para poder distinguir una
    # página genuinamente nueva de una ventana con nombre ya existente
    # que el sitio simplemente reutiliza/renaviega (ej. el popup de
    # anexos, que usa window.open(..., 'MercadoPublico', ...))
    pages_before = set(context.pages)
    frames_before = set(scan_page.frames)

    # Caso 1: descarga directa
    try:
        with scan_page.expect_download(timeout=6000) as dl_info:
            el.click()
        download = dl_info.value
        suggested = download.suggested_filename or f"{label}.pdf"
        dest = out_dir / f"{licitacion_id}_{suggested}"
        download.save_as(str(dest))
        return dest
    except PWTimeout:
        print(f"      [debug] {label!r} caso 1 (descarga directa): timeout, no disparó evento de descarga")
    except Exception as e:
        print(f"      [debug] {label!r} caso 1 (descarga directa) falló: {e!r}")

    # Caso 2: se abrió una ventana/pestaña GENUINAMENTE nueva
    try:
        scan_page.wait_for_timeout(1500)
        new_pages = [p for p in context.pages if p not in pages_before]
        print(f"      [debug] {label!r} caso 2: {len(new_pages)} pestaña(s) nueva(s) tras el click"
              + (f" (url={new_pages[-1].url!r})" if new_pages else ""))
        candidate_page = new_pages[-1] if new_pages else None
        if candidate_page and candidate_page.url and candidate_page.url != "about:blank":
            candidate_page.wait_for_load_state("load", timeout=10000)
            file_url = candidate_page.url
            resp = context.request.get(file_url)
            content_type = resp.headers.get("content-type", "").lower()
            is_file = resp.ok and "text/html" not in content_type
            print(f"      [debug] {label!r} caso 2: GET {file_url!r} -> status={resp.status} "
                  f"content-type={content_type!r}")
            if is_file:
                body = resp.body()
                ext = Path(file_url.split("?")[0]).suffix
                if not ext or len(ext) > 5:
                    ext = ".pdf" if "pdf" in content_type else ".bin"
                dest = out_dir / f"{licitacion_id}_{label}{ext}"
                dest.write_bytes(body)
                candidate_page.close()
                return dest
            candidate_page.close()
    except Exception as e:
        print(f"      [debug] {label!r} caso 2 falló: {e!r}")

    # Caso 3: postback ASP.NET que deja el archivo en un <iframe> dentro de
    # la MISMA página (ej. "Ver Anexo": abre el PDF en un panel/lightbox
    # embebido en vez de una pestaña nueva o una descarga real).
    try:
        new_frames = [f for f in scan_page.frames if f not in frames_before]
        print(f"      [debug] {label!r} caso 3: {len(new_frames)} iframe(s) nuevo(s) tras el click"
              + (f" (url={new_frames[-1].url!r})" if new_frames else ""))
        frame = new_frames[-1] if new_frames else None
        if frame and frame.url and frame.url != "about:blank":
            file_url = frame.url
            resp = context.request.get(file_url)
            content_type = resp.headers.get("content-type", "").lower()
            is_file = resp.ok and "text/html" not in content_type
            print(f"      [debug] {label!r} caso 3: GET {file_url!r} -> status={resp.status} "
                  f"content-type={content_type!r}")
            if is_file:
                body = resp.body()
                ext = Path(file_url.split("?")[0]).suffix
                if not ext or len(ext) > 5:
                    ext = ".pdf" if "pdf" in content_type else ".bin"
                dest = out_dir / f"{licitacion_id}_{label}{ext}"
                dest.write_bytes(body)
                return dest
    except Exception as e:
        print(f"      [debug] {label!r} caso 3 falló: {e!r}")

    return None


def download_all(licitacion_id: str, out_dir: Path, headless: bool = False):
    # OJO: headless=True dispara el bloqueo anti-bot del portal (403.html).
    # Con ventana visible (headless=False) el navegador no queda marcado
    # como automatizado y funciona normal. No forzar headless=True.
    out_dir.mkdir(parents=True, exist_ok=True)
    url = BASE_URL.format(licitacion_id)

    with sync_playwright() as p:
        # El popup de adjuntos (ViewAttachment/ViewAttachmentLC) redirige a
        # /Procurement/403.html -- confirmado que es un bloqueo por IP de
        # datacenter (Azure), no fingerprinting del browser: el mismo codigo
        # funciona sin tocar nada desde un IP residencial, y enmascarar
        # navigator.webdriver + el renderer de WebGL no cambio el resultado.
        # SCRAPER_PROXY (opcional): si esta seteada, Chromium sale a internet
        # a traves de ese proxy en vez de directo -- pensado para un SOCKS5
        # local que tunelea hacia un IP residencial (ver ts-scraper-proxy en
        # docker-compose.yml, el sidecar de Tailscale). Sin la variable, se
        # comporta igual que siempre (sale directo).
        proxy_url = os.environ.get("SCRAPER_PROXY")
        launch_kwargs = {
            "headless": headless,
            "args": ["--disable-blink-features=AutomationControlled"],
        }
        if proxy_url:
            launch_kwargs["proxy"] = {"server": proxy_url}
            print(f"[*] Usando proxy: {proxy_url}")
        browser = p.chromium.launch(**launch_kwargs)
        context = browser.new_context(
            accept_downloads=True,
            user_agent=("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"),
        )
        # Se re-ejecuta en cada documento/navegacion nueva (init script),
        # asi que tambien cubre la ventana popup de adjuntos.
        context.add_init_script(
            "Object.defineProperty(navigator, 'webdriver', { get: () => undefined });"
        )
        # En una VM sin GPU real, WebGL reporta un renderer de software
        # (ej. "SwiftShader") en vez de una GPU real -- es una señal de
        # "esto es un bot/VM" bastante mas dificil de esconder que
        # navigator.webdriver, y probablemente la causa real de que el
        # popup de adjuntos rebote a 403.html incluso con el fix anterior.
        # Se intercepta getParameter() para que UNMASKED_VENDOR_WEBGL /
        # UNMASKED_RENDERER_WEBGL (los codigos que expone la extension
        # WEBGL_debug_renderer_info) devuelvan una GPU real en vez del
        # renderer de software -- el resto de los parametros pasan igual.
        context.add_init_script("""
            (() => {
                const spoof = (proto) => {
                    if (!proto) return;
                    const original = proto.getParameter;
                    proto.getParameter = function (parameter) {
                        if (parameter === 37445) return 'Intel Inc.';
                        if (parameter === 37446) return 'Intel Iris OpenGL Engine';
                        return original.apply(this, arguments);
                    };
                };
                spoof(window.WebGLRenderingContext && window.WebGLRenderingContext.prototype);
                spoof(window.WebGL2RenderingContext && window.WebGL2RenderingContext.prototype);
            })();
        """)
        # Sin esto, un confirm()/alert() nativo de JS (ej. el "Descargar
        # seleccionados" hace uno preguntando si continuar por el peso del
        # archivo) deja el click() colgado hasta el timeout, sin avisar nada.
        context.on("dialog", lambda dialog: dialog.accept())

        page = context.new_page()

        print(f"[*] Cargando ficha: {url}")
        page.goto(url, wait_until="networkidle", timeout=60000)
        page.wait_for_timeout(1500)  # deja asentar cualquier JS diferido

        downloaded = []

        # --- Paso 1: en la ficha, SOLO buscamos el botón de bases y el
        # link a anexos (nada de búsqueda genérica acá) ---
        bases_btn = find_bases_button(page)
        if bases_btn:
            dest = try_click_and_get_file(context, page, bases_btn, "Bases", out_dir, licitacion_id)
            if dest:
                print(f"[*] Bases descargadas: {dest.name}")
                downloaded.append(dest)
            else:
                print("[!] Se encontró el botón de bases pero no se pudo descargar.")
        else:
            print("[!] No se encontró el botón de bases (#descargar_pdf_baseFirmada).")

        popup = None
        attach_link = find_attachment_page_link(page)
        if attach_link:
            print("[*] Encontrado control de anexos (imgAdjuntos), haciendo click real "
                  "(se abre en ventana popup nueva)...")
            with page.context.expect_page(timeout=15000) as popup_info:
                attach_link.click()
            popup = popup_info.value
            popup.wait_for_load_state("networkidle", timeout=60000)
            popup.wait_for_timeout(1000)
            print(f"[*] Popup de anexos cargado: {popup.url}")
        else:
            print("[!] No se encontró el control de anexos (imgAdjuntos). "
                  "Revisá el HTML de debug de la ficha para ver cómo está armado.")

        # --- Paso 2: en la página de anexos, ahí SÍ hacemos la búsqueda
        # genérica de todo lo descargable ---
        if popup:
            candidates = find_download_candidates(popup)
            print(f"[*] En la página de anexos encontrados {len(candidates)} controles candidatos:")
            for i, c in enumerate(candidates):
                print(f"    [{i}] text={c['text']!r} id={c['id']!r} onclick={c['onclick']!r} href={c['href']!r}")

            for i, c in enumerate(candidates):
                # Re-consultamos el elemento por id justo antes de clickear en
                # vez de reusar la referencia de mas arriba: si un click
                # anterior disparo un postback ASP.NET que recargo el grid
                # (visto en la practica con licitaciones de archivos grandes),
                # las referencias viejas quedan "Element is not attached to
                # the DOM". Los ids se mantienen iguales despues del postback.
                if c["id"]:
                    el = popup.query_selector(f"[id='{c['id']}']")
                    if el is None:
                        print(f"    -> [{i}] id={c['id']!r} ya no existe tras un postback anterior, se ignora")
                        continue
                else:
                    el = c["el"]
                label = re.sub(r"[^\w\-]+", "_", (c["text"] or c["id"] or f"anexo_{i}")).strip("_") or f"anexo_{i}"
                dest = try_click_and_get_file(context, popup, el, label, out_dir, licitacion_id)
                if dest:
                    print(f"    -> descargado: {dest.name}")
                    downloaded.append(dest)
                else:
                    print(f"    -> [{i}] {label!r} no se pudo descargar, se ignora")

        browser.close()

        print(f"\n[✓] Listo. {len(downloaded)} archivo(s) descargado(s) en {out_dir}")
        return downloaded


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("licitacion_id", help="ID de la licitación, ej: 903031-10-L126")
    parser.add_argument("--out", default="./descargas", help="Carpeta de salida")
    args = parser.parse_args()

    download_all(
        args.licitacion_id,
        Path(args.out),
    )