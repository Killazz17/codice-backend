package edu.backend.codice

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/*
 * Límite de intentos para frenar la fuerza bruta y el abuso de la IA.
 *
 * - Login, registro y búsqueda de código de invitación: por dirección IP, por minuto. Sin esto se pueden probar
 *   contraseñas o códigos de invitación sin parar.
 * - Llamadas a la IA (cuestan dinero y cuota de Gemini): por usuario (su token), por hora.
 *
 * Corre ANTES de Spring Security (orden más alto) para que el login del filtro de seguridad también quede cubierto.
 * Los contadores viven en memoria de cada instancia de Cloud Run: con varias instancias el límite real es por instancia,
 * lo que sigue frenando un ataque desde una sola máquina. Si se necesitara un límite global habría que moverlo a un
 * almacén compartido (Redis) o a Cloud Armor.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RateLimitFilter(
    @Value("\${rate-limit.auth-per-minute:30}") private val authPerMinute: Int,
    @Value("\${rate-limit.ai-per-hour:60}") private val aiPerHour: Int,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {

    private class Window(val start: Long, val count: AtomicInteger)

    private class Rule(val key: String, val limit: Int, val windowMs: Long)

    private val windows = ConcurrentHashMap<String, Window>()

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val rule = ruleFor(request)
        if (rule != null) {
            val retryAfter = hit(rule.key, rule.limit, rule.windowMs)
            if (retryAfter != null) {
                reject(request, response, retryAfter)
                return
            }
        }
        chain.doFilter(request, response)
    }

    private fun ruleFor(request: HttpServletRequest): Rule? {
        val path = request.requestURI
        val post = request.method == "POST"
        return when {
            post && AUTH_PATHS.any { path.endsWith(it) } -> Rule("auth:" + clientIp(request), authPerMinute, MINUTE_MS)
            request.method == "GET" && path.contains("/invite/") -> Rule("auth:" + clientIp(request), authPerMinute, MINUTE_MS)
            post && AI_SUFFIXES.any { path.endsWith(it) } ->
                Rule("ai:" + (request.getHeader("Authorization") ?: clientIp(request)), aiPerHour, HOUR_MS)
            else -> null
        }
    }

    /** Cuenta un intento. Devuelve `null` si pasa, o los segundos que faltan para que se libere la ventana. */
    private fun hit(key: String, limit: Int, windowMs: Long): Long? {
        val now = System.currentTimeMillis()
        if (windows.size > MAX_TRACKED) windows.entries.removeIf { now - it.value.start >= HOUR_MS }
        val window = windows.compute(key) { _, old ->
            if (old == null || now - old.start >= windowMs) Window(now, AtomicInteger(1)) else old.also { it.count.incrementAndGet() }
        }!!
        return if (window.count.get() > limit) (window.start + windowMs - now) / 1000 + 1 else null
    }

    // Detrás de Cloud Run, quien llega al servidor es el balanceador de Google: la IP real del cliente es la ÚLTIMA entrada
    // de X-Forwarded-For (las anteriores las puede escribir el propio cliente para esquivar el límite).
    private fun clientIp(request: HttpServletRequest): String =
        request.getHeader("X-Forwarded-For")?.split(',')?.lastOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: request.remoteAddr

    private fun reject(request: HttpServletRequest, response: HttpServletResponse, retryAfterSeconds: Long) {
        response.status = HttpStatus.TOO_MANY_REQUESTS.value()
        response.setHeader("Retry-After", retryAfterSeconds.toString())
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        val body = ErrorResponse(
            timestamp = Instant.now(),
            status = HttpStatus.TOO_MANY_REQUESTS.value(),
            error = "Too Many Requests",
            message = "Demasiados intentos. Espera $retryAfterSeconds segundos e inténtalo de nuevo",
            path = request.requestURI,
        )
        response.writer.write(objectMapper.writeValueAsString(body))
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 3_600_000L
        const val MAX_TRACKED = 10_000
        // change-password también: con un token robado se podrían probar contraseñas actuales sin parar.
        val AUTH_PATHS = listOf("/auth/login", "/auth/signup", "/auth/register", "/auth/change-password", "/v1/users/login")
        val AI_SUFFIXES = listOf("/temario/extraer", "/apuntes/extraer", "/transcripcion", "/auditar", "/repasos/generar")
    }
}
