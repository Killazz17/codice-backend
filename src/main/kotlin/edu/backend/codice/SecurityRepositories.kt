package edu.backend.codice

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.Optional

/*
 * Repositorios de identidad (issue #9 del Lab 4): usuarios, roles y privilegios.
 *
 * Patrón Repositorio: cada interfaz es la única puerta de acceso a su tabla. Los servicios y la
 * capa de seguridad piden datos por aquí y nunca escriben SQL ni conocen el motor; si mañana
 * cambia la base, solo cambia lo que hay detrás de estas interfaces. Spring Data genera la
 * implementación a partir del nombre del método, así que aquí solo caben consultas: la lógica de
 * negocio (validar una contraseña, decidir un rol) vive en los servicios, no en el repositorio.
 */

@Repository
interface UserRepository : JpaRepository<User, Long> {

    /**
     * Busca un usuario por correo. Lo usa el inicio de sesión (UserDetailsService): el correo es el
     * nombre de usuario y es único en la tabla.
     */
    fun findByEmail(email: String): Optional<User>

    /**
     * Dice si un correo ya está registrado sin cargar al usuario. Lo usa el registro para rechazar
     * un correo repetido con un error claro antes de intentar insertar.
     */
    fun existsByEmail(email: String): Boolean
}

@Repository
interface RoleRepository : JpaRepository<Role, Long> {

    /**
     * Busca un rol por nombre (`ROLE_STUDENT` o `ROLE_TEACHER`). Lo usa el registro para asignarle
     * a la cuenta nueva el rol que eligió.
     */
    fun findByName(name: String): Optional<Role>
}

// Los privilegios se consultan por id o completos; no necesita consultas propias por ahora.
@Repository
interface PrivilegeRepository : JpaRepository<Privilege, Long>
