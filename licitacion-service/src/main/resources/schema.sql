-- Esquema de cache/historico para licitacion-service.
-- licitacion-service es un proxy sobre la API de Licitaciones de Mercado
-- Publico; estas tablas guardan una copia local de lo que se va
-- consultando, para poder servir listados sin pegarle siempre a la API
-- externa y para conservar historico aunque la API deje de exponerlo.
--
-- Compra Agil vive en su propia base (compra_service_db, ver
-- compra-service/schema.sql) desde el split de compra-service en 2
-- servicios -- ninguna tabla de acá tiene FK cruzada hacia esa base
-- (nunca la hubo: el split de dominio ya era limpio desde antes).
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

-- CREATE TABLE IF NOT EXISTS no toca una tabla que ya existe, asi que las
-- columnas agregadas despues del deploy inicial de "licitaciones" se
-- suman a mano aca para que las instalaciones existentes tambien las reciban.
ALTER TABLE licitaciones ADD COLUMN IF NOT EXISTS dias_cierre_licitacion VARCHAR(20);
ALTER TABLE licitaciones ADD COLUMN IF NOT EXISTS modalidad INTEGER;
ALTER TABLE licitaciones ADD COLUMN IF NOT EXISTS tipo_pago VARCHAR(20);
ALTER TABLE licitaciones ADD COLUMN IF NOT EXISTS tiempo VARCHAR(20);
ALTER TABLE licitaciones ADD COLUMN IF NOT EXISTS unidad_tiempo VARCHAR(20);
ALTER TABLE licitaciones ADD COLUMN IF NOT EXISTS tiempo_duracion_contrato VARCHAR(20);
ALTER TABLE licitaciones ADD COLUMN IF NOT EXISTS unidad_tiempo_duracion_contrato INTEGER;
ALTER TABLE licitaciones ADD COLUMN IF NOT EXISTS es_renovable INTEGER;
ALTER TABLE licitaciones ADD COLUMN IF NOT EXISTS fuente_financiamiento TEXT;
ALTER TABLE licitaciones ADD COLUMN IF NOT EXISTS nombre_responsable_pago TEXT;
ALTER TABLE licitaciones ADD COLUMN IF NOT EXISTS email_responsable_pago TEXT;
ALTER TABLE licitaciones ADD COLUMN IF NOT EXISTS nombre_responsable_contrato TEXT;
ALTER TABLE licitaciones ADD COLUMN IF NOT EXISTS email_responsable_contrato TEXT;
ALTER TABLE licitaciones ADD COLUMN IF NOT EXISTS fono_responsable_contrato VARCHAR(50);
ALTER TABLE licitaciones ADD COLUMN IF NOT EXISTS rut_unidad VARCHAR(20);
ALTER TABLE licitaciones ADD COLUMN IF NOT EXISTS codigo_unidad VARCHAR(50);
ALTER TABLE licitaciones ADD COLUMN IF NOT EXISTS direccion_unidad TEXT;
ALTER TABLE licitaciones ADD COLUMN IF NOT EXISTS rut_usuario VARCHAR(20);
ALTER TABLE licitaciones ADD COLUMN IF NOT EXISTS codigo_usuario VARCHAR(50);
ALTER TABLE licitaciones ADD COLUMN IF NOT EXISTS nombre_usuario TEXT;
ALTER TABLE licitaciones ADD COLUMN IF NOT EXISTS cargo_usuario TEXT;
ALTER TABLE licitaciones ADD COLUMN IF NOT EXISTS fecha_creacion TIMESTAMP;
ALTER TABLE licitaciones ADD COLUMN IF NOT EXISTS fecha_inicio TIMESTAMP;
ALTER TABLE licitaciones ADD COLUMN IF NOT EXISTS fecha_final TIMESTAMP;
ALTER TABLE licitaciones ADD COLUMN IF NOT EXISTS fecha_pub_respuestas TIMESTAMP;
ALTER TABLE licitaciones ADD COLUMN IF NOT EXISTS fecha_acto_apertura_tecnica TIMESTAMP;
ALTER TABLE licitaciones ADD COLUMN IF NOT EXISTS fecha_acto_apertura_economica TIMESTAMP;
ALTER TABLE licitaciones ADD COLUMN IF NOT EXISTS fecha_visita_terreno TIMESTAMP;
ALTER TABLE licitaciones ADD COLUMN IF NOT EXISTS fecha_entrega_antecedentes TIMESTAMP;
ALTER TABLE licitaciones ADD COLUMN IF NOT EXISTS fecha_estimada_adjudicacion TIMESTAMP;
ALTER TABLE licitaciones ADD COLUMN IF NOT EXISTS fecha_estimada_firma TIMESTAMP;
ALTER TABLE licitaciones ADD COLUMN IF NOT EXISTS fecha_soporte_fisico TIMESTAMP;

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

-- Adjuntos de licitaciones normales (LS/LP/LE), bajados por scraper-service
-- (Playwright) via LicitacionAttachmentScraperClient y orquestados por
-- LicitacionSyncScheduler. El binario se guarda completo en "contenido":
-- no hay copia en disco, la BD es la unica fuente de verdad.
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
ALTER TABLE adjunto_licitacion ADD COLUMN IF NOT EXISTS tipo_contenido VARCHAR(150);
ALTER TABLE adjunto_licitacion ADD COLUMN IF NOT EXISTS contenido BYTEA;
