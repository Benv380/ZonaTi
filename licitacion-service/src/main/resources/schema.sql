-- Esquema de cache/historico para licitacion-service.
-- Este servicio es un proxy sobre la API publica de Licitaciones de Mercado
-- Publico (LS/LP/LE), mas la descarga de sus adjuntos via scraping
-- (Playwright, ver scripts/descargar_adjuntos.py). Estas tablas guardan una
-- copia local de lo que se va consultando, para poder servir listados sin
-- pegarle siempre a la API externa y para conservar historico aunque la API
-- deje de exponerlo.
--
-- ddl-auto esta en "none" (ver application.yml) a proposito: el esquema se
-- controla a mano con este script. Se ejecuta solo con
-- spring.sql.init.mode=always y usa CREATE TABLE IF NOT EXISTS para poder
-- correr en cada arranque sin romper si ya existe.

CREATE TABLE IF NOT EXISTS licitaciones (
    codigo_externo                      VARCHAR(50) PRIMARY KEY,
    nombre                              TEXT,
    codigo_estado                       INTEGER,
    estado                              VARCHAR(100),
    descripcion                         TEXT,
    moneda                              VARCHAR(10),
    monto_estimado                      NUMERIC(18, 2),
    tipo                                VARCHAR(100),
    dias_cierre_licitacion              VARCHAR(20),
    modalidad                           INTEGER,
    tipo_pago                           VARCHAR(20),
    tiempo                              VARCHAR(20),
    unidad_tiempo                       VARCHAR(20),
    tiempo_duracion_contrato            VARCHAR(20),
    unidad_tiempo_duracion_contrato     INTEGER,
    es_renovable                        INTEGER,
    fuente_financiamiento               TEXT,
    nombre_responsable_pago             TEXT,
    email_responsable_pago              TEXT,
    nombre_responsable_contrato         TEXT,
    email_responsable_contrato          TEXT,
    fono_responsable_contrato           VARCHAR(50),
    -- Comprador
    codigo_organismo                    VARCHAR(50),
    nombre_organismo                    TEXT,
    rut_unidad                          VARCHAR(20),
    codigo_unidad                       VARCHAR(50),
    nombre_unidad                       TEXT,
    direccion_unidad                    TEXT,
    comuna_unidad                       VARCHAR(100),
    region_unidad                       VARCHAR(100),
    rut_usuario                         VARCHAR(20),
    codigo_usuario                      VARCHAR(50),
    nombre_usuario                      TEXT,
    cargo_usuario                       TEXT,
    -- Fechas
    fecha_creacion                      TIMESTAMP,
    fecha_publicacion                   TIMESTAMP,
    fecha_cierre                        TIMESTAMP,
    fecha_inicio                        TIMESTAMP,
    fecha_final                         TIMESTAMP,
    fecha_pub_respuestas                TIMESTAMP,
    fecha_acto_apertura_tecnica         TIMESTAMP,
    fecha_acto_apertura_economica       TIMESTAMP,
    fecha_visita_terreno                TIMESTAMP,
    fecha_entrega_antecedentes          TIMESTAMP,
    fecha_estimada_adjudicacion         TIMESTAMP,
    fecha_adjudicacion                  TIMESTAMP,
    fecha_estimada_firma                TIMESTAMP,
    fecha_soporte_fisico                TIMESTAMP,
    fecha_sync                          TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_licitaciones_fecha_publicacion ON licitaciones (fecha_publicacion);

-- Items (productos/servicios solicitados) del detalle de una licitacion
-- (Listado[].Items.Listado). Solo llega en el detalle por codigo; se
-- resincroniza entero en cada guardado (ver LicitacionMapper).
CREATE TABLE IF NOT EXISTS licitacion_items (
    id                          BIGSERIAL PRIMARY KEY,
    licitacion_codigo_externo   VARCHAR(50) NOT NULL REFERENCES licitaciones (codigo_externo) ON DELETE CASCADE,
    correlativo                 INTEGER,
    codigo_producto              BIGINT,
    codigo_categoria             VARCHAR(50),
    categoria                    TEXT,
    nombre_producto               TEXT,
    descripcion                  TEXT,
    unidad_medida                 VARCHAR(50),
    cantidad                     NUMERIC(18, 4)
);
CREATE INDEX IF NOT EXISTS idx_licitacion_items_codigo ON licitacion_items (licitacion_codigo_externo);

-- Adjuntos de licitaciones normales (LS/LP/LE), bajados por
-- scraper-service (servicio Python aparte, Playwright) y orquestados por
-- LicitacionSyncScheduler. El binario se guarda completo en "contenido": no
-- hay copia en disco, la BD es la unica fuente de verdad.
--
-- OJO: "contenido" es byte[] SIN @Lob en la entidad a proposito -- con
-- @Lob, Hibernate mapea byte[] a un "Large Object" de Postgres (referenciado
-- por un OID/bigint), no a esta columna BYTEA (bug real que salio en
-- produccion la primera vez que se armo esto).
CREATE TABLE IF NOT EXISTS adjunto_licitacion (
    id                  BIGSERIAL PRIMARY KEY,
    codigo_licitacion   VARCHAR(50) NOT NULL REFERENCES licitaciones (codigo_externo) ON DELETE CASCADE,
    nombre_archivo      TEXT NOT NULL,
    tipo_contenido      VARCHAR(150),
    tamano_bytes        INTEGER,
    contenido           BYTEA,
    fecha_sync          TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_adjunto_licitacion_codigo ON adjunto_licitacion (codigo_licitacion);
