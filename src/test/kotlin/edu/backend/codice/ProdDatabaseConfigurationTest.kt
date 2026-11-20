package edu.backend.codice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ProdDatabaseConfigurationTest {

    @Test
    fun `sin CLOUD_SQL_INSTANCE arma la URL JDBC con host, puerto y SSL obligatorio`() {
        val settings = ProdDatabaseConfiguration.parse("postgres://codice:secreta@db.ejemplo.com:6543/codice", null)

        assertEquals("jdbc:postgresql://db.ejemplo.com:6543/codice?sslmode=require", settings.jdbcUrl)
        assertEquals("codice", settings.username)
        assertEquals("secreta", settings.password)
    }

    @Test
    fun `sin puerto en DATABASE_URL usa 5432`() {
        val settings = ProdDatabaseConfiguration.parse("postgres://codice:secreta@db.ejemplo.com/codice", null)

        assertEquals("jdbc:postgresql://db.ejemplo.com:5432/codice?sslmode=require", settings.jdbcUrl)
    }

    @Test
    fun `acepta el esquema postgresql ademas de postgres`() {
        val settings = ProdDatabaseConfiguration.parse("postgresql://codice:secreta@db.ejemplo.com:5432/codice", null)

        assertEquals("jdbc:postgresql://db.ejemplo.com:5432/codice?sslmode=require", settings.jdbcUrl)
    }

    @Test
    fun `con CLOUD_SQL_INSTANCE usa el conector de Cloud SQL e ignora el host`() {
        val settings = ProdDatabaseConfiguration.parse(
            "postgres://codice:secreta@localhost/codice",
            "proyecto:us-central1:codice-db",
        )

        assertEquals(
            "jdbc:postgresql:///codice?cloudSqlInstance=proyecto:us-central1:codice-db" +
                "&socketFactory=com.google.cloud.sql.postgres.SocketFactory",
            settings.jdbcUrl,
        )
        assertEquals("codice", settings.username)
        assertEquals("secreta", settings.password)
    }

    @Test
    fun `CLOUD_SQL_INSTANCE en blanco se trata como ausente`() {
        val settings = ProdDatabaseConfiguration.parse("postgres://codice:secreta@db.ejemplo.com:5432/codice", "  ")

        assertEquals("jdbc:postgresql://db.ejemplo.com:5432/codice?sslmode=require", settings.jdbcUrl)
    }

    @Test
    fun `una contrasena con caracteres codificados se decodifica`() {
        val settings = ProdDatabaseConfiguration.parse("postgres://codice:a%40b%3Ac@db.ejemplo.com:5432/codice", null)

        assertEquals("a@b:c", settings.password)
    }

    @Test
    fun `DATABASE_URL sin usuario y contrasena falla con un mensaje claro`() {
        val error = assertThrows<IllegalStateException> {
            ProdDatabaseConfiguration.parse("postgres://db.ejemplo.com:5432/codice", null)
        }

        assertEquals("DATABASE_URL debe tener el formato postgres://usuario:clave@host:puerto/base", error.message)
    }
}
