package edu.backend.codice

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import edu.backend.codice.AppCustomDsl.Companion.customDsl
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import java.time.Instant

/*
 * Los handlers de 401/403 de Spring Security escriben el JSON a mano, sin pasar por el ObjectMapper de Spring MVC.
 * findAndRegisterModules agrega Kotlin y las fechas (Instant de ErrorResponse); sin eso la respuesta fallaba y
 * el cliente recibía la página HTML de Tomcat. Las fechas salen en ISO-8601, igual que en el resto de la API.
 */
internal val securityErrorMapper: ObjectMapper = ObjectMapper()
    .findAndRegisterModules()
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class JwtSecurityConfiguration(
    @Lazy private val userDetailsService: UserDetailsService
) {

    @Bean
    @Throws(java.lang.Exception::class)
    fun authenticationManager(authConfig: AuthenticationConfiguration): AuthenticationManager? {
        return authConfig.authenticationManager
    }

    @Bean
    fun passwordEncoder(): BCryptPasswordEncoder? {
        return BCryptPasswordEncoder()
    }

    @Bean
    fun authenticationProvider(): DaoAuthenticationProvider? {
        val authProvider = DaoAuthenticationProvider()
        authProvider.setUserDetailsService(userDetailsService)
        authProvider.setPasswordEncoder(passwordEncoder())
        return authProvider
    }

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { it.configurationSource(corsConfigurationSource()) }
            .exceptionHandling {
                it.authenticationEntryPoint { request, response, _ ->
                    response.contentType = "application/json;charset=UTF-8"
                    response.status = HttpServletResponse.SC_UNAUTHORIZED
                    val errorResponse = ErrorResponse(
                        timestamp = Instant.now(),
                        status = 401,
                        error = "Unauthorized",
                        // Texto fijo: el de Spring ("Full authentication is required...") llegaría en inglés al cliente.
                        message = "Autenticación requerida: envíe un token válido en el header Authorization",
                        path = request.requestURI
                    )
                    response.writer.write(securityErrorMapper.writeValueAsString(errorResponse))
                }
                it.accessDeniedHandler { request, response, accessDeniedException ->
                    response.contentType = "application/json;charset=UTF-8"
                    response.status = HttpServletResponse.SC_FORBIDDEN
                    val errorResponse = ErrorResponse(
                        timestamp = Instant.now(),
                        status = 403,
                        error = "Forbidden",
                        message = accessDeniedException.message ?: "Acceso denegado",
                        path = request.requestURI
                    )
                    response.writer.write(securityErrorMapper.writeValueAsString(errorResponse))
                }
            }
            .authorizeHttpRequests {
                it
                    // Salud (la usan los health checks de Cloud Run) y documentación: públicas.
                    .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    .requestMatchers("/api-docs/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                    // Solo el registro y el login son públicos. Antes lo era todo /api/v1/auth/**, y con eso /me se
                    // podía llamar sin token. /signup es el nombre que ya usa la app móvil para el registro.
                    // Sin filtrar por verbo: un verbo no soportado (DELETE /api/v1/auth/login) debe llegar a Spring MVC y
                    // responder 405, no 401 por falta de token.
                    .requestMatchers("/api/v1/auth/register", "/api/v1/auth/signup", "/api/v1/auth/login").permitAll()
                    .anyRequest().authenticated()
            }
            .sessionManagement {
                it.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            }
            .authenticationProvider(authenticationProvider())
            .apply(customDsl(userDetailsService))

        return http.build()
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val source = UrlBasedCorsConfigurationSource()
        val config = CorsConfiguration().apply {
            allowCredentials = true
            addAllowedOrigin("http://localhost:3000")
            addAllowedHeader("*")
            addAllowedMethod("*")
        }
        source.registerCorsConfiguration("/**", config)
        return source
    }
}

class AppCustomDsl(private val userDetailsService: UserDetailsService) : AbstractHttpConfigurer<AppCustomDsl?, HttpSecurity?>() {
    override fun configure(http: HttpSecurity?) {
        super.configure(builder)
        val authenticationManager = http?.getSharedObject(
            AuthenticationManager::class.java
        )

        http?.addFilter(JwtAuthenticationFilter(authenticationManager!!))
        http?.addFilter(JwtAuthorizationFilter(authenticationManager!!, userDetailsService))
    }

    companion object {
        fun customDsl(userDetailsService: UserDetailsService): AppCustomDsl {
            return AppCustomDsl(userDetailsService)
        }
    }
}

@Configuration
class OpenApiConfig {
    @Bean
    fun customOpenAPI(): OpenAPI {
        val securitySchemeName = "bearerAuth"
        return OpenAPI()
            .info(
                Info()
                    .title("Códice REST API")
                    .version("1.0.0")
                    .description("Documentación oficial de servicios de la plataforma Códice")
            )
            .addSecurityItem(SecurityRequirement().addList(securitySchemeName))
            .components(
                Components()
                    .addSecuritySchemes(
                        securitySchemeName,
                        SecurityScheme()
                            .name(securitySchemeName)
                            .type(SecurityScheme.Type.HTTP)
                            .scheme("bearer")
                            .bearerFormat("JWT")
                    )
            )
    }
}
