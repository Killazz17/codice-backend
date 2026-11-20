package edu.backend.codice

import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.security.Key
import java.time.Instant
import java.util.Date

interface JwtService {
    fun generateToken(user: User): String
    fun validateToken(token: String): Boolean
    fun getEmailFromToken(token: String): String
    fun getUserIdFromToken(token: String): Long
    fun getRoleFromToken(token: String): String
    fun getExpirationFromToken(token: String): Instant
}

@Service
class JwtServiceImpl(
    // Sin jwt.secret se usa el mismo secreto del filtro JWT (SecurityConstants.TOKEN_SECRET: JWT_SECRET o, solo en
    // desarrollo, la llave local). Antes tenía una llave distinta por defecto y el token que entregaba
    // /api/v1/auth/login no lo aceptaba JwtAuthorizationFilter. En prod jwt.secret ya es JWT_SECRET.
    @Value("\${jwt.secret:}")
    private val secretKeyString: String,

    @Value("\${jwt.expiration-ms:864000000}")
    private val expirationMs: Long
) : JwtService {

    private fun getSigningKey(): Key {
        val secret = secretKeyString.ifBlank { SecurityConstants.TOKEN_SECRET }
        val keyBytes = try {
            Decoders.BASE64.decode(secret)
        } catch (e: Exception) {
            secret.toByteArray(Charsets.UTF_8)
        }
        return Keys.hmacShaKeyFor(keyBytes)
    }

    override fun generateToken(user: User): String {
        val now = Date()
        val expiryDate = Date(now.time + expirationMs)
        val role = user.roleList.firstOrNull()?.name ?: "ROLE_STUDENT"

        return Jwts.builder()
            .setSubject(user.email)
            .claim("id", user.id)
            .claim("email", user.email)
            .claim("role", role)
            .setIssuedAt(now)
            .setExpiration(expiryDate)
            .signWith(getSigningKey(), SignatureAlgorithm.HS512)
            .compact()
    }

    override fun validateToken(token: String): Boolean {
        return try {
            val claims = parseClaims(token)
            !claims.expiration.before(Date())
        } catch (e: JwtException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    override fun getEmailFromToken(token: String): String {
        return parseClaims(token).subject
    }

    override fun getUserIdFromToken(token: String): Long {
        return (parseClaims(token)["id"] as Number).toLong()
    }

    override fun getRoleFromToken(token: String): String {
        return (parseClaims(token)["role"] as? String) ?: "ROLE_STUDENT"
    }

    override fun getExpirationFromToken(token: String): Instant {
        return parseClaims(token).expiration.toInstant()
    }

    private fun parseClaims(token: String): Claims {
        return Jwts.parserBuilder()
            .setSigningKey(getSigningKey())
            .build()
            .parseClaimsJws(token)
            .body
    }
}
