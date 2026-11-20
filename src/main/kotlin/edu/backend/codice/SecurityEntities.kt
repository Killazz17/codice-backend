package edu.backend.codice

import jakarta.persistence.*
import java.time.Instant

/*
 * Agregado Seguridad: usuarios, roles y privilegios (modelo RBAC que usa Spring Security).
 *
 * Códice tiene dos roles, ROLE_STUDENT y ROLE_TEACHER (el CHECK de role.name no admite otros).
 * Un usuario tiene roles y cada rol tiene privilegios; Spring Security convierte ambos en
 * GrantedAuthority (ver AppUserDetailsService), así cada endpoint puede autorizar por rol o por
 * privilegio.
 *
 * `class` en vez de `data class`: la data class imprimía la contraseña en toString y, con las
 * relaciones ManyToMany, recorría los roles al calcular hashCode. Aquí equals/hashCode van por id
 * y toString no incluye ni relaciones ni la contraseña.
 */

@Entity
@Table(name = "users") // "user" es palabra reservada en PostgreSQL
class User(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "first_name", nullable = false)
    var firstName: String = "",

    @Column(name = "last_name", nullable = false)
    var lastName: String = "",

    // El correo es el nombre de usuario para iniciar sesión: por eso es único.
    @Column(nullable = false, unique = true)
    var email: String = "",

    // Hash BCrypt, nunca la contraseña en claro.
    @Column(nullable = false)
    var password: String = "",

    @Column(nullable = false)
    var enabled: Boolean = true,

    @Column(name = "token_expired", nullable = false)
    var tokenExpired: Boolean = false,

    @Column(name = "create_date", nullable = false)
    var createDate: Instant = Instant.now(),

    // EAGER porque Spring Security necesita los roles en cada autenticación y un usuario tiene uno o
    // dos: cargarlos siempre es más barato que una segunda consulta perezosa fuera de transacción.
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "user_role",
        joinColumns = [JoinColumn(name = "user_id", referencedColumnName = "id")],
        inverseJoinColumns = [JoinColumn(name = "role_id", referencedColumnName = "id")],
    )
    var roleList: MutableSet<Role> = mutableSetOf(),
) {
    override fun equals(other: Any?) = this === other || (other is User && id != null && id == other.id)
    override fun hashCode() = User::class.hashCode()
    override fun toString() = "User(id=$id, email=$email, enabled=$enabled)"
}

@Entity
@Table(name = "role")
class Role(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    // ROLE_STUDENT o ROLE_TEACHER; el prefijo ROLE_ es el que espera hasRole() de Spring Security.
    @Column(nullable = false, unique = true, length = 50)
    var name: String = "",

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "role_privilege",
        joinColumns = [JoinColumn(name = "role_id", referencedColumnName = "id")],
        inverseJoinColumns = [JoinColumn(name = "privilege_id", referencedColumnName = "id")],
    )
    var privilegeList: MutableSet<Privilege> = mutableSetOf(),
) {
    override fun equals(other: Any?) = this === other || (other is Role && id != null && id == other.id)
    override fun hashCode() = Role::class.hashCode()
    override fun toString() = "Role(id=$id, name=$name)"
}

@Entity
@Table(name = "privilege")
class Privilege(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(nullable = false, unique = true, length = 80)
    var name: String = "",
) {
    override fun equals(other: Any?) = this === other || (other is Privilege && id != null && id == other.id)
    override fun hashCode() = Privilege::class.hashCode()
    override fun toString() = "Privilege(id=$id, name=$name)"
}
