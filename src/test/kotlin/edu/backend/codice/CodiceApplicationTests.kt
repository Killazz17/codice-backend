package edu.backend.codice

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

// Levanta el contexto completo de Spring (seguridad, servicios, repositorios) contra H2.
@SpringBootTest
@ActiveProfiles("test")
class CodiceApplicationTests {

    @Test
    fun contextLoads() {
    }
}
