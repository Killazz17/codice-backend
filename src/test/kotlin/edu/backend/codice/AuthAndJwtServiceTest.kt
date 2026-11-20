package edu.backend.codice

import edu.backend.codice.exception.BusinessRuleException
import edu.backend.codice.exception.ConflictException
import edu.backend.codice.exception.UnauthorizedException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.util.Optional

class AuthAndJwtServiceTest {

    private lateinit var userRepository: UserRepository
    private lateinit var roleRepository: RoleRepository
    private lateinit var passwordEncoder: BCryptPasswordEncoder
    private lateinit var jwtService: JwtService
    private lateinit var authService: AuthService

    @BeforeEach
    fun setUp() {
        userRepository = mock()
        roleRepository = mock()
        passwordEncoder = BCryptPasswordEncoder()
        jwtService = JwtServiceImpl(
            secretKeyString = "======================MikeEducationMikeEducationMikeEducationMikeEducationMikeEducationMikeEducationMikeEducation===========================",
            expirationMs = 3600000L
        )
        authService = AbstractAuthService(userRepository, roleRepository, passwordEncoder, jwtService)
    }

    // ==================== JWT SERVICE TESTS ====================

    @Test
    fun `test JwtService generate and validate valid token`() {
        val user = User(
            id = 42L,
            firstName = "Carlos",
            lastName = "Mora",
            email = "carlos@example.com",
            password = "hashedPassword",
            roleList = mutableSetOf(Role(id = 1L, name = "ROLE_TEACHER"))
        )

        val token = jwtService.generateToken(user)
        assertNotNull(token)

        assertTrue(jwtService.validateToken(token))
        assertEquals("carlos@example.com", jwtService.getEmailFromToken(token))
        assertEquals(42L, jwtService.getUserIdFromToken(token))
        assertEquals("ROLE_TEACHER", jwtService.getRoleFromToken(token))
    }

    @Test
    fun `test JwtService validate tampered token returns false`() {
        val user = User(id = 1L, email = "test@example.com")
        val token = jwtService.generateToken(user)
        val tamperedToken = token + "corrupted"

        assertFalse(jwtService.validateToken(tamperedToken))
    }

    @Test
    fun `test JwtService validate expired token returns false`() {
        val expiredJwtService = JwtServiceImpl(
            secretKeyString = "======================MikeEducationMikeEducationMikeEducationMikeEducationMikeEducationMikeEducationMikeEducation===========================",
            expirationMs = -1000L // Expirado en el pasado
        )
        val user = User(id = 1L, email = "test@example.com")
        val token = expiredJwtService.generateToken(user)

        assertFalse(expiredJwtService.validateToken(token))
    }

    // ==================== AUTH SERVICE TESTS ====================

    @Test
    fun `test signUp success`() {
        val signUpInput = UserSignUpRequest(
            firstName = "Laura",
            lastName = "Jiménez",
            email = "laura@example.com",
            password = "rawPassword123",
            roleName = "ROLE_TEACHER"
        )

        whenever(userRepository.findByEmail("laura@example.com")).thenReturn(Optional.empty())
        whenever(roleRepository.findByName("ROLE_TEACHER")).thenReturn(Optional.of(Role(id = 2L, name = "ROLE_TEACHER")))
        whenever(userRepository.save(any<User>())).thenAnswer { invocation ->
            val userToSave = invocation.getArgument<User>(0)
            userToSave.id = 10L
            userToSave
        }

        val result = authService.signUp(signUpInput)
        assertEquals(10L, result.id)
        assertEquals("laura@example.com", result.email)
        assertTrue(result.roles.contains("ROLE_TEACHER"))
    }

    @Test
    fun `test signUp duplicate email throws ConflictException`() {
        val signUpInput = UserSignUpRequest(
            email = "carlos@example.com",
            password = "pass",
            roleName = "ROLE_STUDENT"
        )

        whenever(userRepository.findByEmail("carlos@example.com"))
            .thenReturn(Optional.of(User(id = 1L, email = "carlos@example.com")))

        assertThrows(ConflictException::class.java) {
            authService.signUp(signUpInput)
        }
    }

    @Test
    fun `test signUp invalid role throws BusinessRuleException`() {
        val signUpInput = UserSignUpRequest(
            email = "admin@example.com",
            password = "pass",
            roleName = "ROLE_ADMIN" // Rol no permitido en Códice
        )

        assertThrows(BusinessRuleException::class.java) {
            authService.signUp(signUpInput)
        }
    }

    @Test
    fun `test login success returns AuthResponse with token`() {
        val rawPass = "mySecretPassword"
        val hashedPass = passwordEncoder.encode(rawPass)
        val user = User(
            id = 55L,
            email = "ana@example.com",
            password = hashedPass,
            roleList = mutableSetOf(Role(id = 1L, name = "ROLE_STUDENT"))
        )

        whenever(userRepository.findByEmail("ana@example.com")).thenReturn(Optional.of(user))

        val loginInput = UserLoginRequest(username = "ana@example.com", password = rawPass)
        val response = authService.login(loginInput)

        assertNotNull(response.token)
        assertEquals("Bearer", response.tokenType)
        assertEquals(55L, response.user.id)
        assertEquals("ana@example.com", response.user.email)
    }

    @Test
    fun `test login incorrect password throws UnauthorizedException`() {
        val hashedPass = passwordEncoder.encode("correctPassword")
        val user = User(id = 1L, email = "ana@example.com", password = hashedPass)

        whenever(userRepository.findByEmail("ana@example.com")).thenReturn(Optional.of(user))

        val loginInput = UserLoginRequest(username = "ana@example.com", password = "wrongPassword")
        assertThrows(UnauthorizedException::class.java) {
            authService.login(loginInput)
        }
    }

    @Test
    fun `test login user not found throws UnauthorizedException`() {
        whenever(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty())

        val loginInput = UserLoginRequest(username = "unknown@example.com", password = "any")
        assertThrows(UnauthorizedException::class.java) {
            authService.login(loginInput)
        }
    }
}
