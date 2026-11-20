package edu.backend.codice

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.sql.DataSource

/**
 * DataSource del perfil prod, armado desde variables de entorno.
 *
 * - DATABASE_URL (obligatoria): postgres://usuario:clave@host:puerto/base
 * - CLOUD_SQL_INSTANCE (opcional): proyecto:region:instancia. Si está, la conexión va por el conector
 *   de Cloud SQL (socket autenticado con IAM, sin abrir la base a internet) y el host de DATABASE_URL
 *   se ignora. Si no está, se conecta directo al host con SSL obligatorio.
 */
@Configuration
@Profile("prod")
class ProdDatabaseConfiguration {

    data class DatabaseSettings(val jdbcUrl: String, val username: String, val password: String)

    @Bean
    fun dataSource(): DataSource {
        val databaseUrl = System.getenv("DATABASE_URL")
            ?: throw IllegalStateException("La variable de entorno DATABASE_URL no está configurada")
        val settings = parse(databaseUrl, System.getenv("CLOUD_SQL_INSTANCE"))

        val hikariConfig = HikariConfig().apply {
            this.jdbcUrl = settings.jdbcUrl
            this.username = settings.username
            this.password = settings.password
            this.driverClassName = "org.postgresql.Driver"
            this.maximumPoolSize = 5
            this.connectionTimeout = 30000
        }

        return HikariDataSource(hikariConfig)
    }

    companion object {
        private const val FORMATO = "DATABASE_URL debe tener el formato postgres://usuario:clave@host:puerto/base"

        fun parse(databaseUrl: String, cloudSqlInstance: String?): DatabaseSettings {
            val dbUri = URI(databaseUrl.replaceFirst(Regex("^postgres(ql)?://"), "http://"))
            val userInfo = dbUri.rawUserInfo?.split(":", limit = 2)
            if (userInfo == null || userInfo.size != 2) throw IllegalStateException(FORMATO)

            val username = decode(userInfo[0])
            val password = decode(userInfo[1])
            val database = dbUri.path.removePrefix("/")

            val jdbcUrl = if (!cloudSqlInstance.isNullOrBlank()) {
                "jdbc:postgresql:///$database?cloudSqlInstance=${cloudSqlInstance.trim()}" +
                    "&socketFactory=com.google.cloud.sql.postgres.SocketFactory"
            } else {
                val port = if (dbUri.port != -1) dbUri.port else 5432
                "jdbc:postgresql://${dbUri.host}:$port/$database?sslmode=require"
            }

            return DatabaseSettings(jdbcUrl, username, password)
        }

        private fun decode(value: String) = URLDecoder.decode(value, StandardCharsets.UTF_8)
    }
}
