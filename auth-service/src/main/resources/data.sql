-- Usuario admin inicial (bootstrap). Se ejecuta despues de schema.sql en
-- cada arranque (spring.sql.init.mode=always), por eso el ON CONFLICT DO
-- NOTHING -- no pisa la fila si ya existe (por ejemplo, si ya cambiaste la
-- password a mano).
--
-- El hash de abajo es BCrypt (factor 12, ver SecurityConfig.passwordEncoder)
-- de una password generada al azar -- la password en texto plano NO se
-- versiona aca. Se entrego una sola vez fuera del repo; si se perdio,
-- genera una nueva y reemplaza este hash (no hay endpoint de cambio de
-- password todavia).
INSERT INTO users (name, last_name, role, email, username, password)
VALUES (
    'Admin',
    'ZonaTi',
    'ADMIN',
    'be.marfull@duocuc.cl',
    'admin',
    '$2a$12$0s34iV9ly3mod.XMwfU6Vex.tXGFbAlhmgw1U3Kgmft2lLKgHo4rC'
)
ON CONFLICT (username) DO NOTHING;
