-- Seed de roles + bootstrap del usuario admin. Se ejecuta despues de
-- schema.sql en cada arranque (spring.sql.init.mode=always), por eso todo
-- va con ON CONFLICT DO NOTHING / WHERE ... IS NULL -- no pisa nada si ya
-- corrio antes.

-- alcance determina los permisos reales (ver comentario en schema.sql).
-- Antes existia tambien LIDER_SUPREMO como segundo nombre para el mismo
-- nivel GLOBAL -- se saco (decision explicita): 2 nombres para el mismo
-- permiso era redundante, y no es un nombre serio para un producto real.
-- Queda un unico rol GLOBAL: ADMIN.
INSERT INTO roles (nombre, alcance, descripcion) VALUES
    ('ADMIN',         'GLOBAL',  'Acceso total, cualquier empresa'),
    ('ADMIN_EMPRESA', 'EMPRESA', 'Administra su propia empresa: perfil de busqueda y sus usuarios'),
    ('USER',          'USUARIO', 'Busqueda de solo lectura con el filtro heredado de su empresa')
ON CONFLICT (nombre) DO NOTHING;

-- Limpieza de LIDER_SUPREMO si quedo de un arranque anterior (bases que
-- ya venian corriendo con el rol viejo, ej. la VM): reasigna a ADMIN
-- cualquier usuario que lo tuviera ANTES de borrar la fila del catalogo
-- (si no, la FK de users.role_id impediria el DELETE). En una base nueva,
-- donde LIDER_SUPREMO nunca se creo, esto no hace nada -- las subconsultas
-- no encuentran la fila y quedan como no-op.
UPDATE users SET role_id = (SELECT id FROM roles WHERE nombre = 'ADMIN')
WHERE role_id = (SELECT id FROM roles WHERE nombre = 'LIDER_SUPREMO');
DELETE FROM roles WHERE nombre = 'LIDER_SUPREMO';

-- Backfill: si la tabla users ya tenia filas de una version anterior de
-- este archivo (con la columna "role" de texto libre, sin role_id todavia
-- asignado), se les asigna un role_id ahora. El username 'admin' puntual
-- es la cuenta real del dueño del sistema -> ADMIN. Cualquier otra fila
-- vieja sin rol asignado cae a USER por defecto (ajustar a mano si
-- corresponde otro rol).
UPDATE users SET role_id = (SELECT id FROM roles WHERE nombre = 'ADMIN')
WHERE username = 'admin' AND role_id IS NULL;
UPDATE users SET role_id = (SELECT id FROM roles WHERE nombre = 'USER')
WHERE role_id IS NULL;

-- Usuario admin inicial (bootstrap), con rol ADMIN y sin empresa (los
-- roles GLOBAL no pertenecen a una empresa puntual).
--
-- El hash de abajo es BCrypt (factor 12, ver SecurityConfig.passwordEncoder)
-- de la password elegida por el usuario -- la password en texto plano NO
-- se versiona aca. Si se perdio, genera una nueva y reemplaza este hash
-- (no hay endpoint de cambio de password todavia).
--
-- OJO: por el ON CONFLICT DO NOTHING de abajo, este INSERT no pisa la fila
-- si el username 'admin' ya existe en la base. Si ya tenias un usuario
-- 'admin' insertado con otra password, hay que actualizarla a mano:
--   UPDATE users SET password = '<hash>' WHERE username = 'admin';
INSERT INTO users (name, last_name, email, username, password, role_id)
VALUES (
    'Admin',
    'ZonaTi',
    'be.marfull@duocuc.cl',
    'admin',
    '$2b$12$gJaQI1toUx9NfnvJi.aN1uJC5Zb8VgDq/7hLt0nyW7Ik5ilsewDhG',
    (SELECT id FROM roles WHERE nombre = 'ADMIN')
)
ON CONFLICT (username) DO NOTHING;
