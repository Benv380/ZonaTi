-- Corre UNA sola vez, al primer arranque del contenedor de Postgres en
-- VM3 (ver docker-compose.vm3.yml) -- crea las 3 bases, una por servicio.
-- Cada schema.sql propio (auth-service, compra-service, licitacion-service)
-- se encarga de las tablas dentro de la suya, esto solo deja las bases
-- listas para que existan.
CREATE DATABASE auth_service_db;
CREATE DATABASE compra_service_db;
CREATE DATABASE licitacion_service_db;
