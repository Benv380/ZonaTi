-- Esquema de auth-service (login + emision de JWT).
--
-- TEMPORAL: esta tabla vive en licitacion_service_db (base compartida con
-- licitacion-service) mientras no se provisiona una base propia
-- (auth_service_db) -- ver comentario en application.yml. No tiene FKs hacia
-- ninguna tabla de licitacion-service ni al reves; son independientes, solo
-- comparten el mismo servidor Postgres por ahora.
--
-- ddl-auto esta en "none" (ver application.yml) a proposito, igual que en
-- compra-service/licitacion-service: el esquema se controla a mano aca. Se
-- ejecuta con spring.sql.init.mode=always y usa CREATE TABLE IF NOT EXISTS
-- para poder correr en cada arranque sin romper si ya existe.
--
-- Los nombres de columna siguen la convencion default de Hibernate
-- (SpringPhysicalNamingStrategy: camelCase -> snake_case), para que la
-- entidad User (Model/User.java) mapee sin anotaciones @Column extra.
CREATE TABLE IF NOT EXISTS users (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(150),
    last_name   VARCHAR(150),
    role        VARCHAR(50)  NOT NULL DEFAULT 'USER',
    email       VARCHAR(255),
    username    VARCHAR(100) NOT NULL,
    -- Hash BCrypt (ver PasswordEncoder en SecurityConfig), nunca texto plano.
    password    VARCHAR(255) NOT NULL,
    CONSTRAINT uq_users_username UNIQUE (username)
);
