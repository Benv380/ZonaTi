-- Esquema de auth-service (login + emision de JWT + gestion de usuarios/
-- empresas/roles). Base de datos propia (auth_service_db) -- no comparte
-- tablas con compra-service.
--
-- ddl-auto esta en "none" (ver application.yml): el esquema se controla a
-- mano aca. Se ejecuta con spring.sql.init.mode=always y usa
-- CREATE TABLE IF NOT EXISTS / ADD COLUMN IF NOT EXISTS para poder correr
-- en cada arranque sin romper si ya existe.
--
-- Modelo multi-tenant: cada usuario pertenece (opcionalmente) a una
-- empresa, y el filtro de busqueda generado por el LLM (rubro/palabras
-- clave/region) vive a nivel de EMPRESA, no de usuario individual -- lo
-- configura el ADMIN_EMPRESA una vez (representa el rubro del negocio) y
-- lo heredan todos los usuarios asignados a esa empresa.
--
-- Los nombres de columna siguen la convencion default de Hibernate
-- (SpringPhysicalNamingStrategy: camelCase -> snake_case).

CREATE TABLE IF NOT EXISTS empresas (
    id          BIGSERIAL PRIMARY KEY,
    nombre      VARCHAR(200) NOT NULL,
    rut         VARCHAR(20),
    creado_en   TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_empresas_rut UNIQUE (rut)
);

-- Datos de registro mas completos que el "nombre + rut" original -- ver
-- Administracion.jsx (formulario "Nueva empresa"). Igual que con "users"
-- mas arriba, se agregan con ADD COLUMN IF NOT EXISTS para no romper
-- instalaciones que ya tenian la tabla creada con el diseño viejo.
ALTER TABLE empresas ADD COLUMN IF NOT EXISTS rubro VARCHAR(150);
ALTER TABLE empresas ADD COLUMN IF NOT EXISTS representante_legal VARCHAR(200);
ALTER TABLE empresas ADD COLUMN IF NOT EXISTS direccion VARCHAR(255);
ALTER TABLE empresas ADD COLUMN IF NOT EXISTS comuna VARCHAR(100);
ALTER TABLE empresas ADD COLUMN IF NOT EXISTS region VARCHAR(100);
ALTER TABLE empresas ADD COLUMN IF NOT EXISTS telefono VARCHAR(30);
ALTER TABLE empresas ADD COLUMN IF NOT EXISTS email VARCHAR(255);

-- Segunda tanda -- se pidio explicitamente "que no quede nada fuera".
-- "rubro" (arriba) es el que se usa para FILTRAR licitaciones
-- (perfil_busqueda) -- "giro" es distinto: la actividad economica oficial
-- registrada en el SII, un dato administrativo/legal, no de filtrado.
ALTER TABLE empresas ADD COLUMN IF NOT EXISTS nombre_fantasia VARCHAR(200);
ALTER TABLE empresas ADD COLUMN IF NOT EXISTS giro VARCHAR(200);
ALTER TABLE empresas ADD COLUMN IF NOT EXISTS rut_representante_legal VARCHAR(20);
-- Clasificacion oficial por tamaño (Micro/Pequeña/Mediana/Grande) -- en
-- Mercado Publico las Empresas de Menor Tamaño (EMT, ya aparece ese
-- concepto en compra_agil_proveedores_cotizando.es_emt de compra-service)
-- tienen reglas y beneficios distintos.
ALTER TABLE empresas ADD COLUMN IF NOT EXISTS tamano_empresa VARCHAR(20);
-- Inscripcion en el registro oficial de proveedores del Estado -- sin
-- esto, en la practica, no se puede ofertar en la mayoria de las
-- licitaciones.
ALTER TABLE empresas ADD COLUMN IF NOT EXISTS chileproveedores_registrado BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE empresas ADD COLUMN IF NOT EXISTS chileproveedores_codigo VARCHAR(50);
ALTER TABLE empresas ADD COLUMN IF NOT EXISTS sitio_web VARCHAR(255);
-- Logo: mismo patron que "adjuntos"/"adjunto_licitacion" en compra-service
-- -- el binario completo va en la BD (BYTEA), sin copia en disco. OJO: la
-- entidad JPA NO debe anotar este campo con @Lob (rompe con "column logo
-- is of type bytea but expression is of type bigint" -- bug real ya
-- pisado con los adjuntos, ver Empresa.java).
ALTER TABLE empresas ADD COLUMN IF NOT EXISTS logo BYTEA;
ALTER TABLE empresas ADD COLUMN IF NOT EXISTS logo_tipo_contenido VARCHAR(100);
-- Soft-disable: para poder desactivar una empresa sin borrarla (a
-- diferencia de eliminar(), que ademas exige que no tenga usuarios).
ALTER TABLE empresas ADD COLUMN IF NOT EXISTS estado VARCHAR(20) NOT NULL DEFAULT 'ACTIVA';
-- A diferencia de perfil_busqueda.actualizado_en (que quedo sin usar --
-- nunca se setea desde el codigo), este SI se completa en cada
-- crear/actualizar (ver EmpresaService.aplicarDatos).
ALTER TABLE empresas ADD COLUMN IF NOT EXISTS actualizado_en TIMESTAMP;

-- Texto libre para el pie de una cotizacion generada (ver
-- MarcaCotizacionService/generarCotizacionPdf.js) -- cada empresa define
-- sus propios terminos comerciales, en vez del texto fijo que tenia antes
-- el generador de PDF.
ALTER TABLE empresas ADD COLUMN IF NOT EXISTS condiciones_comerciales TEXT;

-- Color principal de la cotizacion en PDF (hex, ej. "#A61928") -- se
-- elige de una paleta fija chica (ver MiEmpresa.jsx), no un selector
-- libre. NULL usa el rojo por defecto (ver generarCotizacionPdf.js).
ALTER TABLE empresas ADD COLUMN IF NOT EXISTS color_principal VARCHAR(7);

-- Datos bancarios para el pie de la cotizacion en PDF (ver
-- MarcaCotizacionService/generarCotizacionPdf.js) -- mismo criterio que
-- condiciones_comerciales/color_principal: editable desde "Base de
-- cotización" en Mi Empresa, no desde Administracion.
ALTER TABLE empresas ADD COLUMN IF NOT EXISTS banco VARCHAR(100);
ALTER TABLE empresas ADD COLUMN IF NOT EXISTS tipo_cuenta VARCHAR(50);
ALTER TABLE empresas ADD COLUMN IF NOT EXISTS numero_cuenta VARCHAR(50);

-- Credenciales de Chile Compra (Mercado Publico) para el flujo automatico
-- de envio de cotizaciones (Power Automate Desktop) -- ver
-- ChileCompraCredencialesService. La password NUNCA se guarda en texto
-- plano, siempre cifrada (AES-256-GCM, clave fuera de la base -- ver
-- CHILECOMPRA_ENCRYPTION_KEY en application.yml). TEXT porque el
-- resultado cifrado (IV + texto cifrado + tag) en base64 pesa mas que un
-- VARCHAR corto.
ALTER TABLE empresas ADD COLUMN IF NOT EXISTS chile_compra_usuario VARCHAR(255);
ALTER TABLE empresas ADD COLUMN IF NOT EXISTS chile_compra_password_cifrada TEXT;

ALTER TABLE empresas DROP CONSTRAINT IF EXISTS chk_empresas_tamano;
ALTER TABLE empresas ADD CONSTRAINT chk_empresas_tamano
    CHECK (tamano_empresa IS NULL OR tamano_empresa IN ('MICRO', 'PEQUENA', 'MEDIANA', 'GRANDE'));

ALTER TABLE empresas DROP CONSTRAINT IF EXISTS chk_empresas_estado;
ALTER TABLE empresas ADD CONSTRAINT chk_empresas_estado CHECK (estado IN ('ACTIVA', 'INACTIVA'));

-- Catalogo de roles. "alcance" es lo que realmente determina los permisos
-- en el codigo (no el nombre) -- si en algun momento se agrega otro
-- nombre con el mismo nivel de permiso, no hace falta duplicar logica de
-- autorizacion en cada endpoint, solo comparten "alcance".
--   GLOBAL   -> acceso total, cualquier empresa (ADMIN).
--   EMPRESA  -> acotado a la propia empresa (ADMIN_EMPRESA): puede editar
--               el perfil_busqueda de su empresa y agregar usuarios, pero
--               siempre dentro de su propia empresa y con rol USER (ver
--               UsuarioService -- evita que se auto-escale privilegios).
--   USUARIO  -> solo lectura/busqueda con el filtro heredado (USER).
CREATE TABLE IF NOT EXISTS roles (
    id          BIGSERIAL PRIMARY KEY,
    nombre      VARCHAR(50) NOT NULL,
    alcance     VARCHAR(20) NOT NULL,
    descripcion VARCHAR(255),
    CONSTRAINT uq_roles_nombre UNIQUE (nombre),
    CONSTRAINT chk_roles_alcance CHECK (alcance IN ('GLOBAL', 'EMPRESA', 'USUARIO'))
);

CREATE TABLE IF NOT EXISTS users (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(150),
    last_name   VARCHAR(150),
    email       VARCHAR(255),
    username    VARCHAR(100) NOT NULL,
    -- Hash BCrypt (ver PasswordEncoder en SecurityConfig), nunca texto plano.
    password    VARCHAR(255) NOT NULL,
    role_id     BIGINT REFERENCES roles(id),
    -- NULL para roles GLOBAL (ADMIN, no pertenecen a una
    -- empresa puntual). La app exige que no sea NULL para ADMIN_EMPRESA/
    -- USER -- no se valida con NOT NULL aca porque la misma tabla sirve
    -- para ambos casos.
    empresa_id  BIGINT REFERENCES empresas(id),
    CONSTRAINT uq_users_username UNIQUE (username)
);

-- Migracion desde el esquema viejo (una columna "role" de texto libre
-- suelta, sin tabla roles/empresas): si la tabla users ya existia de una
-- version anterior de este archivo, agrega las columnas nuevas sin tocar
-- la columna "role" vieja. No se dropea sola a proposito -- un DROP COLUMN
-- corriendo en cada arranque es arriesgado si el backfill de data.sql
-- tuviera un bug y se perdiera el dato. Confirmado que todo quedo bien
-- migrado a role_id/empresa_id, se puede borrar a mano con:
--   ALTER TABLE users DROP COLUMN role;
ALTER TABLE users ADD COLUMN IF NOT EXISTS role_id BIGINT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS empresa_id BIGINT;

-- Marca administrativa (tipicamente "es nuevo en el sistema") que solo
-- puede ver/editar un ADMIN_EMPRESA o GLOBAL -- nunca se expone al propio
-- usuario marcado (ni en UsuarioResponse cuando lo pide el, ni en el JWT).
-- Mientras esta en true, cuando el propio usuario completa una asignacion
-- suya, ademas de pasar a COMPLETADO le prende "pendiente_revision" (ver
-- esa columna en "asignaciones") -- no bloquea el flujo de estados, solo
-- la deja marcada para que un admin la revise despues.
ALTER TABLE users ADD COLUMN IF NOT EXISTS requiere_supervision BOOLEAN NOT NULL DEFAULT false;

-- El login ahora es por email, no por username (ver AuthService.authenticate)
-- -- username se mantiene igual (sigue siendo el identificador que se
-- muestra en el resto de la app: tablas de asignaciones, "quien la hizo",
-- etc.), pero deja de ser lo que se escribe para entrar. Cualquier fila
-- vieja sin email (la columna era opcional antes de este cambio) se rellena
-- con un placeholder derivado del username, para no dejar a nadie sin poder
-- entrar nunca mas -- un admin lo corrige desde "Editar usuario" apenas
-- entre.
UPDATE users SET email = username || '@pendiente.local' WHERE email IS NULL;
ALTER TABLE users ALTER COLUMN email SET NOT NULL;

ALTER TABLE users DROP CONSTRAINT IF EXISTS uq_users_email;
ALTER TABLE users ADD CONSTRAINT uq_users_email UNIQUE (email);

-- Postgres no soporta "ADD CONSTRAINT IF NOT EXISTS" -- en vez de un
-- bloque DO $$ ... $$ (Spring corta el script por ";" de forma ingenua y
-- no entiende el dollar-quoting de Postgres, asi que un DO con ";" adentro
-- rompe el arranque -- "Unterminated dollar quote", ya lo pisamos en la
-- VM), se resuelve con 2 statements simples: DROP CONSTRAINT IF EXISTS
-- (soportado nativo) + ADD CONSTRAINT. Igual de idempotente, sin bloque.
ALTER TABLE users DROP CONSTRAINT IF EXISTS fk_users_role;
ALTER TABLE users ADD CONSTRAINT fk_users_role FOREIGN KEY (role_id) REFERENCES roles(id);

ALTER TABLE users DROP CONSTRAINT IF EXISTS fk_users_empresa;
ALTER TABLE users ADD CONSTRAINT fk_users_empresa FOREIGN KEY (empresa_id) REFERENCES empresas(id);

-- Filtro de busqueda generado por el LLM en el onboarding (ver
-- PerfilController/OpenAiClient) -- 1:1 con empresas, no con users.
CREATE TABLE IF NOT EXISTS perfil_busqueda (
    empresa_id          BIGINT PRIMARY KEY REFERENCES empresas(id),
    rubro                VARCHAR(100),
    palabras_clave       VARCHAR(255),
    region_codigo        VARCHAR(10),
    region_nombre        VARCHAR(100),
    perfil_completado    BOOLEAN NOT NULL DEFAULT false,
    actualizado_en       TIMESTAMP
);

-- Asignacion manual e individual de una licitacion/compra agil puntual a
-- un usuario -- capa ADICIONAL sobre perfil_busqueda, no un reemplazo: el
-- filtro de empresa sigue acotando el universo por rubro/region, y esto le
-- suma a un USER curaduria fina hecha a mano por un ADMIN_EMPRESA/GLOBAL
-- (ver AsignacionController en compra-service -- la lectura la hace
-- compra-service consultando este servicio en vivo en cada listado, no se
-- cachea en el JWT, para que un cambio de asignacion se vea al instante).
CREATE TABLE IF NOT EXISTS asignaciones (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    codigo_externo VARCHAR(50) NOT NULL,
    -- 'LICITACION' o 'COMPRA_AGIL' -- distingue de que dominio es el
    -- codigo, porque compra-service tiene 2 entidades separadas con sus
    -- propios codigos externos.
    tipo           VARCHAR(20) NOT NULL,
    creado_en      TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT uq_asignaciones_user_codigo_tipo UNIQUE (user_id, codigo_externo, tipo),
    CONSTRAINT chk_asignaciones_tipo CHECK (tipo IN ('LICITACION', 'COMPRA_AGIL'))
);

-- "origen" distingue si la fila la creo un admin (ASIGNADA de arriba hacia
-- abajo) o el propio usuario (RECOMENDADA de abajo hacia arriba, sin paso
-- de aprobacion -- queda visible directo para su supervisor). "estado" es
-- el flujo de trabajo sobre ese item puntual; "motivo_descarte"/
-- "etapa_descarte" solo se llenan cuando estado = DESCARTADO, para no
-- perder en que paso se abandono ni por que.
ALTER TABLE asignaciones ADD COLUMN IF NOT EXISTS origen VARCHAR(10) NOT NULL DEFAULT 'ADMIN';
ALTER TABLE asignaciones ADD COLUMN IF NOT EXISTS estado VARCHAR(20) NOT NULL DEFAULT 'ASIGNADO';
ALTER TABLE asignaciones ADD COLUMN IF NOT EXISTS motivo_descarte VARCHAR(500);
ALTER TABLE asignaciones ADD COLUMN IF NOT EXISTS etapa_descarte VARCHAR(20);

-- Igual que con fk_users_role antes: drop-si-existe + add en vez de un
-- bloque DO $$ (rompia el arranque, ver commit que lo corrigio).
ALTER TABLE asignaciones DROP CONSTRAINT IF EXISTS chk_asignaciones_origen;
ALTER TABLE asignaciones ADD CONSTRAINT chk_asignaciones_origen CHECK (origen IN ('ADMIN', 'USER'));

-- Borrador de cotizacion armado en la fase ANALISIS (ver
-- CotizacionCompraAgil.jsx en el front) -- JSON opaco para este servicio,
-- lo arma y calcula el front (lineas de producto, valor unitario,
-- despacho, IVA, totales). TEXT en vez de VARCHAR: una cotizacion con
-- varios productos puede superar facil los limites cortos.
ALTER TABLE asignaciones ADD COLUMN IF NOT EXISTS cotizacion_json TEXT;

-- Texto libre de la fase DESARROLLO (ver DetalleDesarrollo.jsx en el
-- front) -- independiente de la cotizacion de ANALISIS. VARCHAR(255),
-- mismo limite que el campo real de Mercado Publico en el que se basa.
ALTER TABLE asignaciones ADD COLUMN IF NOT EXISTS detalle_desarrollo VARCHAR(255);

-- Si alguna fila ya habia quedado en el estado EN_REVISION (paso que se
-- probo primero y se saco, ver "pendiente_revision" mas abajo), se
-- recupera a DESARROLLO ANTES de sacarlo de los valores permitidos --
-- si no, la constraint de abajo fallaria contra esa fila.
UPDATE asignaciones SET estado = 'DESARROLLO' WHERE estado = 'EN_REVISION';

ALTER TABLE asignaciones DROP CONSTRAINT IF EXISTS chk_asignaciones_estado;
ALTER TABLE asignaciones ADD CONSTRAINT chk_asignaciones_estado
    CHECK (estado IN ('ASIGNADO', 'ANALISIS', 'DESARROLLO', 'COMPLETADO', 'DESCARTADO'));

-- Marca aparte del flujo de estados (a diferencia del EN_REVISION de
-- arriba): cuando un usuario con requiere_supervision en true (ver esa
-- columna en "users") completa una asignacion suya, el estado pasa a
-- COMPLETADO igual que a cualquiera -- no se le frena el trabajo -- pero
-- ademas queda con esta marca en true, y aparece en la lista de
-- "Pendientes de revisión" de su empresa (ver
-- AsignacionService.actualizarEstado/aprobarRevision) hasta que un
-- ADMIN_EMPRESA/GLOBAL la revise y la apruebe.
ALTER TABLE asignaciones ADD COLUMN IF NOT EXISTS pendiente_revision BOOLEAN NOT NULL DEFAULT false;

-- Marca si esta compra agil ya se subio a Mercado Publico -- lo prende un
-- flujo externo (Power Automate Desktop, corre por fuera de este sistema)
-- una vez que efectivamente cargo y envio la cotizacion en el sitio real,
-- via AsignacionService.marcarEnviada. Ese flujo solo debe tomar
-- COMPLETADO con pendiente_revision=false (ya aprobadas) y
-- enviado_mercado_publico=false (todavia no enviadas) -- sin esta marca,
-- cada corrida del flujo reenviaria de nuevo todo lo que ya esta
-- COMPLETADO.
ALTER TABLE asignaciones ADD COLUMN IF NOT EXISTS enviado_mercado_publico BOOLEAN NOT NULL DEFAULT false;

-- Quien recomendo la fila, cuando origen=USER y la mando a OTRA persona
-- (no a si mismo) -- "user_id" pasa a ser el DESTINATARIO (a quien le
-- queda asignada), y este campo guarda quien la origino, para no perder
-- ese dato. NULL para las que son origen=ADMIN, o para una recomendacion
-- a uno mismo (ahi destinatario y recomendador son la misma persona, no
-- hace falta duplicarlo).
ALTER TABLE asignaciones ADD COLUMN IF NOT EXISTS recomendado_por BIGINT REFERENCES users(id);

-- Documentos que se suben a mano en la fase ANALISIS de una asignacion
-- (ver CotizacionCompraAgil.jsx, campo "Documentación necesaria") -- una
-- asignacion puede tener varios, por eso tabla aparte y no una columna
-- mas en "asignaciones". El binario completo va en "contenido" (BYTEA),
-- sin copia en disco -- mismo patron que adjunto_licitacion/adjuntos en
-- compra-service.
CREATE TABLE IF NOT EXISTS asignacion_documento (
    id             BIGSERIAL PRIMARY KEY,
    asignacion_id  BIGINT      NOT NULL REFERENCES asignaciones(id) ON DELETE CASCADE,
    nombre_archivo TEXT        NOT NULL,
    tipo_contenido VARCHAR(150),
    tamano_bytes   INTEGER,
    contenido      BYTEA       NOT NULL,
    creado_en      TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_asignacion_documento_asignacion ON asignacion_documento (asignacion_id);

-- Refresh tokens (ver RefreshTokenService/AuthController): opacos (random,
-- no JWT), guardados aca para poder revocarlos -- el access token (JWT,
-- 1h, autocontenido) nunca se persiste, este si porque tiene que poder
-- invalidarse (logout, rotacion en cada /auth/refresh). "revocado" en vez
-- de borrar la fila: asi queda registro si alguien intenta reusar uno ya
-- gastado.
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token      VARCHAR(255) NOT NULL,
    expira_en  TIMESTAMP    NOT NULL,
    creado_en  TIMESTAMP    NOT NULL DEFAULT now(),
    revocado   BOOLEAN      NOT NULL DEFAULT false,
    CONSTRAINT uq_refresh_tokens_token UNIQUE (token)
);
