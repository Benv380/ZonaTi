"""
Consigue un JWT "anonimo" (rol publico) valido para la API interna/no
oficial de la ficha publica de comprador de Mercado Publico
(comprador-api-pro.mercadopublico.cl) -- necesario porque ese token NO es
el mismo que emite servicios-prd.mercadopublico.cl/v1/auth/publico (el que
ya usan AuthClient/TokenCacheService para las demas APIs de Mercado
Publico): son dos servidores de autenticacion distintos, el token de uno
no sirve para el otro.

No hace falta login con cuenta real: la propia pagina publica de la ficha
de un comprador consigue este token sola al cargar (visible en las
DevTools, pestaña Network, header Authorization de alguna request hacia
comprador-api-pro.mercadopublico.cl). Este script abre esa pagina con un
navegador real e intercepta la primera request hacia ese dominio para
sacarle el token.

Requisitos:
    pip install playwright
    playwright install chromium

Uso:
    python3 obtener_token_reclamos.py <rut_institucion>
    (ej: python3 obtener_token_reclamos.py 61202000-0)

Salida: imprime "TOKEN=<jwt>" como ULTIMA linea de stdout si lo consigue
(el resto son logs de debug, "[*]"/"[!]") -- asi el lado Java
(ReclamosTokenPythonScraper) lo puede parsear sin ambigüedad. Termina con
exit code != 0 si no lo consigue dentro del timeout.
"""

import argparse
import os
import sys

from playwright.sync_api import sync_playwright, TimeoutError as PWTimeout

BASE_URL = "https://comprador.mercadopublico.cl/ficha/{}"
API_HOST = "comprador-api-pro.mercadopublico.cl"


def obtener_token(rut: str, headless: bool = False, timeout_ms: int = 30000):
    token_capturado = {"valor": None}

    with sync_playwright() as p:
        # SCRAPER_PROXY (opcional) tunelea la salida a internet por un
        # SOCKS5 (pensado para un IP residencial) en vez de salir directo
        # -- ver notas de deploy.
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
            user_agent=("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"),
        )
        # Spoofs de navigator.webdriver + renderer WebGL -- no confirmado
        # que este dominio los necesite, pero no cuesta nada dejarlos.
        context.add_init_script(
            "Object.defineProperty(navigator, 'webdriver', { get: () => undefined });"
        )
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

        page = context.new_page()

        def on_request(request):
            if token_capturado["valor"] is not None:
                return
            if API_HOST not in request.url:
                return
            auth = request.headers.get("authorization")
            if auth and auth.lower().startswith("bearer "):
                token_capturado["valor"] = auth[len("Bearer "):].strip()
                print(f"[*] Token capturado desde {request.url}")

        page.on("request", on_request)

        url = BASE_URL.format(rut)
        print(f"[*] Cargando ficha: {url}")
        try:
            page.goto(url, wait_until="networkidle", timeout=timeout_ms)
        except PWTimeout:
            print("[!] Timeout esperando networkidle (puede que el token ya se haya capturado antes).")

        # La request al API interno puede llegar despues de "networkidle"
        # si la dispara JS diferido -- se espera un poco mas si todavia no
        # se capturo nada.
        if token_capturado["valor"] is None:
            page.wait_for_timeout(5000)

        browser.close()

    return token_capturado["valor"]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("rut", help="RUT de cualquier organismo publico, ej: 61202000-0")
    args = parser.parse_args()

    resultado = obtener_token(args.rut)
    if not resultado:
        print("[!] No se pudo capturar el token.", file=sys.stderr)
        sys.exit(1)

    print(f"TOKEN={resultado}")
