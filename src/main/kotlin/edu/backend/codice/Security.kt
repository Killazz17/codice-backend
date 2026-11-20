package edu.backend.codice

import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.AuthenticationServiceException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter
import java.io.IOException
import java.security.Key
import java.util.*

object SecurityConstants {
    const val TOKEN_TYPE = "JWT"
    const val TOKEN_ISSUER = "codice-api"
    const val TOKEN_AUDIENCE = "codice-app"
    const val TOKEN_LIFETIME: Long = 864000000
    const val TOKEN_PREFIX = "Bearer "
    const val APPLICATION_JSON = "application/json"
    const val UTF_8 = "UTF-8"

    // Secreto con el que se firman los JWT (HS512 exige al menos 512 bits, en Base64). En cualquier
    // entorno compartido se define con la variable JWT_SECRET; el valor por defecto es SOLO para
    // desarrollo local: quien lo conozca puede fabricar tokens válidos.
    private const val DEV_TOKEN_SECRET =
        "xUfym8DI2qoQsqTwaZxvvNb9lY73Fj6nsUfbZVdjKXWI/HbenQZQUHkm59iLJGqsb92LUl+yO75owFeNt7GV6A=="
    // En producción la clave pública del repositorio NUNCA se usa: sin JWT_SECRET la app no arranca, en vez de firmar tokens
    // que cualquiera podría fabricar.
    private val isProduction = System.getenv("SPRING_PROFILES_ACTIVE")?.split(',')?.any { it.trim() == "prod" } == true
    val TOKEN_SECRET: String = System.getenv("JWT_SECRET")?.takeIf { it.isNotBlank() }
        ?: if (isProduction) error("JWT_SECRET es obligatorio en producción") else DEV_TOKEN_SECRET
}

class JwtAuthenticationFilter(authenticationManager: AuthenticationManager) : UsernamePasswordAuthenticationFilter() {

    private val authManager: AuthenticationManager

    init {
        setFilterProcessesUrl("/v1/users/login")
        authManager = authenticationManager
    }

    @Throws(AuthenticationException::class)
    override fun attemptAuthentication(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): Authentication {

        if (request.method != "POST") {
            throw AuthenticationServiceException("Authentication method not supported: ${request.method}")
        }

        return try {
            val userLoginInput: UserLoginInput = ObjectMapper()
                .readValue(request.inputStream, UserLoginInput::class.java)
            authManager.authenticate(
                UsernamePasswordAuthenticationToken(
                    userLoginInput.username,
                    userLoginInput.password,
                    ArrayList()
                )
            )
        } catch (exception: IOException) {
            throw RuntimeException(exception)
        }
    }

    override fun successfulAuthentication(
        request: HttpServletRequest, response: HttpServletResponse,
        filterChain: FilterChain, authentication: Authentication,
    ) {

        val objectMapper = ObjectMapper()
        val principal = authentication.principal as org.springframework.security.core.userdetails.User
        // Roles y privilegios: van en la parte pública del token (se ven en jwt.io) y en la respuesta.
        val authorities = principal.authorities.map { it.authority }
        val now = Date()

        val token = Jwts.builder()
            .signWith(key(), SignatureAlgorithm.HS512)
            .setHeaderParam("typ", SecurityConstants.TOKEN_TYPE)
            .setIssuer(SecurityConstants.TOKEN_ISSUER)
            .setAudience(SecurityConstants.TOKEN_AUDIENCE)
            .setSubject(principal.username)
            .claim("authorities", authorities)
            .setIssuedAt(now)
            .setExpiration(Date(now.time + SecurityConstants.TOKEN_LIFETIME))
            .compact()

        response.addHeader(HttpHeaders.AUTHORIZATION, SecurityConstants.TOKEN_PREFIX + token)
        val out = response.writer
        response.contentType = SecurityConstants.APPLICATION_JSON
        response.characterEncoding = SecurityConstants.UTF_8
        // Solo el usuario y sus permisos: nunca la contraseña, ni siquiera vacía.
        out.print(objectMapper.writeValueAsString(mapOf("username" to principal.username, "authorities" to authorities)))
        out.flush()
    }
}

private fun key(): Key {
    return Keys.hmacShaKeyFor(Decoders.BASE64.decode(SecurityConstants.TOKEN_SECRET))
}

/*
 * Autentica cada petición con el token del header Authorization (Bearer). Acepta el token del login clásico
 * (/v1/users/login) y el de POST /api/v1/auth/login: van firmados con el mismo secreto y llevan el correo como
 * subject. El usuario y sus roles se leen de la base en cada petición, así que un cambio de rol o una cuenta
 * deshabilitada surte efecto sin esperar a que venza el token, y @PreAuthorize ve los roles reales (antes la
 * autenticación se creaba sin ninguno).
 *
 * Un token ausente, vencido, manipulado o de un usuario que ya no existe NO corta la petición aquí: la deja sin
 * autenticar, y el control de acceso responde 401 en JSON si la ruta es protegida (o la deja pasar si es pública,
 * por ejemplo un login que llega con un token viejo en el header).
 */
class JwtAuthorizationFilter(
    authenticationManager: AuthenticationManager,
    private val userDetailsService: UserDetailsService,
) : BasicAuthenticationFilter(authenticationManager) {

    private val log = LoggerFactory.getLogger(JwtAuthorizationFilter::class.java)

    @Throws(IOException::class)
    override fun doFilterInternal(
        request: HttpServletRequest, response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        try {
            authenticatedUser(request)?.let { user ->
                LoggedUser.logIn(user.username)
                SecurityContextHolder.getContext().authentication =
                    UsernamePasswordAuthenticationToken(user, null, user.authorities)
            }
            filterChain.doFilter(request, response)
        } finally {
            // Los hilos del servidor se reutilizan: sin esto la petición siguiente heredaba la identidad de esta.
            LoggedUser.logOut()
        }
    }

    private fun authenticatedUser(request: HttpServletRequest): UserDetails? {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION) ?: return null
        if (!header.startsWith(SecurityConstants.TOKEN_PREFIX)) return null
        val token = header.removePrefix(SecurityConstants.TOKEN_PREFIX).trim()

        val username: String? = try {
            Jwts.parserBuilder().setSigningKey(key()).build().parseClaimsJws(token).body.subject
        } catch (e: JwtException) { // vencido, firma inválida, mal formado
            log.debug("Token JWT rechazado: {}", e.message)
            null
        } catch (e: IllegalArgumentException) { // token vacío
            null
        }
        if (username.isNullOrBlank()) return null

        val user = try {
            userDetailsService.loadUserByUsername(username)
        } catch (e: UsernameNotFoundException) {
            return null
        }
        return user.takeIf { it.isEnabled }
    }
}

object LoggedUser {
    private val userHolder = ThreadLocal<String>()
    fun logIn(user: String) {
        userHolder.set(user)
    }

    fun logOut() {
        userHolder.remove()
    }

    fun get(): String {
        return userHolder.get() ?: ""
    }
}
