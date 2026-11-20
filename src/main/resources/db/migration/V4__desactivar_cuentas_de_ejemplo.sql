-- Las cuentas y los códigos de invitación de V2__seed.sql están en el repositorio público y todas comparten la misma
-- contraseña (el mismo hash). En un servidor desplegado cualquiera podría entrar como profesor o unirse a un grupo con
-- el código. Esta migración las desactiva SIN borrar nada: los datos quedan (y se pueden reactivar a mano en una base de
-- desarrollo con UPDATE users SET enabled = true ...), pero ya no se puede iniciar sesión con ellas ni unirse con esos
-- códigos.
UPDATE users
SET enabled = FALSE
WHERE email IN (
    'carlos.mora@example.com',
    'laura.jimenez@example.com',
    'ana.vargas@example.com',
    'luis.rojas@example.com',
    'maria.solis@example.com',
    'diego.araya@example.com'
);

UPDATE student_group
SET invite_expires_at = now() - interval '1 day'
WHERE invite_code IN ('EIF411-01-7K2A', 'EIF411-02-9M4C');
