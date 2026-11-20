package edu.backend.codice

import edu.backend.codice.exception.*
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import java.time.Instant

@RestControllerAdvice
class GlobalExceptionHandler : ResponseEntityExceptionHandler() {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest
    ): ResponseEntity<Any>? {
        val path = (request as? ServletWebRequest)?.request?.requestURI ?: ""
        val fieldErrors = ex.bindingResult.fieldErrors.map {
            FieldErrorDto(field = it.field, message = it.defaultMessage ?: "Valor inválido")
        }
        val errorResponse = ErrorResponse(
            timestamp = Instant.now(),
            status = HttpStatus.BAD_REQUEST.value(),
            error = "Bad Request",
            message = "Violación de validación de campos",
            path = path,
            errors = fieldErrors
        )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).json(errorResponse)
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(ex: ConstraintViolationException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val fieldErrors = ex.constraintViolations.map {
            FieldErrorDto(field = it.propertyPath.toString(), message = it.message)
        }
        val errorResponse = ErrorResponse(
            timestamp = Instant.now(),
            status = HttpStatus.BAD_REQUEST.value(),
            error = "Bad Request",
            message = "Violación de restricciones de validación",
            path = request.requestURI,
            errors = fieldErrors
        )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).json(errorResponse)
    }

    override fun handleHttpMessageNotReadable(
        ex: HttpMessageNotReadableException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest
    ): ResponseEntity<Any>? {
        val path = (request as? ServletWebRequest)?.request?.requestURI ?: ""
        val errorResponse = ErrorResponse(
            timestamp = Instant.now(),
            status = HttpStatus.BAD_REQUEST.value(),
            error = "Bad Request",
            message = "Cuerpo de solicitud JSON mal formado o no leíble",
            path = path
        )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).json(errorResponse)
    }

    override fun handleHttpRequestMethodNotSupported(
        ex: HttpRequestMethodNotSupportedException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest
    ): ResponseEntity<Any>? {
        val path = (request as? ServletWebRequest)?.request?.requestURI ?: ""
        val errorResponse = ErrorResponse(
            timestamp = Instant.now(),
            status = HttpStatus.METHOD_NOT_ALLOWED.value(),
            error = "Method Not Allowed",
            message = "El método HTTP '${ex.method}' no está soportado para esta ruta",
            path = path
        )
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).json(errorResponse)
    }

    override fun handleHttpMediaTypeNotAcceptable(
        ex: HttpMediaTypeNotAcceptableException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest
    ): ResponseEntity<Any>? {
        val path = (request as? ServletWebRequest)?.request?.requestURI ?: ""
        val errorResponse = ErrorResponse(
            timestamp = Instant.now(),
            status = HttpStatus.NOT_ACCEPTABLE.value(),
            error = "Not Acceptable",
            message = "El tipo de medio solicitado no es aceptable",
            path = path
        )
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).json(errorResponse)
    }

    override fun handleHttpMediaTypeNotSupported(
        ex: HttpMediaTypeNotSupportedException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest
    ): ResponseEntity<Any>? {
        val path = (request as? ServletWebRequest)?.request?.requestURI ?: ""
        val errorResponse = ErrorResponse(
            timestamp = Instant.now(),
            status = HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(),
            error = "Unsupported Media Type",
            message = "El tipo de contenido '${ex.contentType}' no está soportado",
            path = path
        )
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).json(errorResponse)
    }

    @ExceptionHandler(UnauthorizedException::class, BadCredentialsException::class)
    fun handleUnauthorized(ex: Exception, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val errorResponse = ErrorResponse(
            timestamp = Instant.now(),
            status = HttpStatus.UNAUTHORIZED.value(),
            error = "Unauthorized",
            message = ex.message ?: "Credenciales inválidas o token no autorizado",
            path = request.requestURI
        )
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).json(errorResponse)
    }

    @ExceptionHandler(ForbiddenException::class, AccessDeniedException::class)
    fun handleForbidden(ex: Exception, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val errorResponse = ErrorResponse(
            timestamp = Instant.now(),
            status = HttpStatus.FORBIDDEN.value(),
            error = "Forbidden",
            message = ex.message ?: "Acceso prohibido para este recurso",
            path = request.requestURI
        )
        return ResponseEntity.status(HttpStatus.FORBIDDEN).json(errorResponse)
    }

    @ExceptionHandler(NotFoundException::class, NoSuchElementException::class)
    fun handleNotFound(ex: Exception, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val errorResponse = ErrorResponse(
            timestamp = Instant.now(),
            status = HttpStatus.NOT_FOUND.value(),
            error = "Not Found",
            message = ex.message ?: "Recurso no encontrado",
            path = request.requestURI
        )
        return ResponseEntity.status(HttpStatus.NOT_FOUND).json(errorResponse)
    }

    // El mensaje de la base trae nombres de tablas, restricciones y fragmentos de SQL: al cliente solo le llega un texto
    // fijo, y el detalle queda en el log.
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrity(ex: DataIntegrityViolationException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        log.warn("Violación de integridad en ${request.requestURI}: ${ex.mostSpecificCause.message}")
        val errorResponse = ErrorResponse(
            timestamp = Instant.now(),
            status = HttpStatus.CONFLICT.value(),
            error = "Conflict",
            message = "El dato ya existe o no cumple las restricciones del servidor",
            path = request.requestURI
        )
        return ResponseEntity.status(HttpStatus.CONFLICT).json(errorResponse)
    }

    @ExceptionHandler(ConflictException::class)
    fun handleConflict(ex: Exception, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val errorResponse = ErrorResponse(
            timestamp = Instant.now(),
            status = HttpStatus.CONFLICT.value(),
            error = "Conflict",
            message = ex.message ?: "Conflicto con el estado actual del recurso",
            path = request.requestURI
        )
        return ResponseEntity.status(HttpStatus.CONFLICT).json(errorResponse)
    }

    @ExceptionHandler(BusinessRuleException::class)
    fun handleBusinessRule(ex: Exception, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val errorResponse = ErrorResponse(
            timestamp = Instant.now(),
            status = HttpStatus.UNPROCESSABLE_ENTITY.value(),
            error = "Unprocessable Entity",
            message = ex.message ?: "Violación de regla de negocio",
            path = request.requestURI
        )
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).json(errorResponse)
    }

    @ExceptionHandler(InvalidInputException::class)
    fun handleInvalidInput(ex: InvalidInputException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val errorResponse = ErrorResponse(
            timestamp = Instant.now(),
            status = HttpStatus.BAD_REQUEST.value(),
            error = "Bad Request",
            message = ex.message ?: "Solicitud inválida",
            path = request.requestURI
        )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).json(errorResponse)
    }

    @ExceptionHandler(AiUnavailableException::class)
    fun handleAiUnavailable(ex: AiUnavailableException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        // El detalle técnico (qué proveedor, qué HTTP) queda en el log; al cliente solo le llega un mensaje accionable.
        log.warn("IA no disponible en ${request.requestURI}: ${ex.message}")
        val errorResponse = ErrorResponse(
            timestamp = Instant.now(),
            status = HttpStatus.SERVICE_UNAVAILABLE.value(),
            error = "Service Unavailable",
            message = "El servicio de IA no está disponible en este momento. Intente de nuevo o ingrese el texto a mano",
            path = request.requestURI
        )
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).json(errorResponse)
    }

    @ExceptionHandler(Exception::class)
    fun handleAllOtherExceptions(ex: Exception, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        log.error("Error no controlado en ${request.requestURI}: ", ex)
        val errorResponse = ErrorResponse(
            timestamp = Instant.now(),
            status = HttpStatus.INTERNAL_SERVER_ERROR.value(),
            error = "Internal Server Error",
            message = "Ocurrió un error interno en el servidor",
            path = request.requestURI
        )
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).json(errorResponse)
    }
}

/*
 * Los errores salen siempre en JSON, aunque el cliente pida otro formato (Accept: application/xml). Sin un Content-Type
 * propio, Spring intenta negociar con el Accept, no encuentra con qué escribir el cuerpo y la respuesta llega vacía.
 */
private fun <T> ResponseEntity.BodyBuilder.json(body: T): ResponseEntity<T> =
    contentType(MediaType.APPLICATION_JSON).body(body)
