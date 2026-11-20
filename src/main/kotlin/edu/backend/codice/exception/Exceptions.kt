package edu.backend.codice.exception

/**
 * Excepción lanzada cuando no se encuentra un recurso solicitado (HTTP 404).
 */
class NotFoundException(message: String) : RuntimeException(message)

/**
 * Excepción lanzada cuando un recurso ya existe o hay un conflicto de estado (HTTP 409).
 */
class ConflictException(message: String) : RuntimeException(message)

/**
 * Excepción lanzada cuando el usuario autenticado no tiene permisos para una acción (HTTP 403).
 */
class ForbiddenException(message: String) : RuntimeException(message)

/**
 * Excepción lanzada cuando las credenciales son inválidas o el token venció/falló (HTTP 401).
 */
class UnauthorizedException(message: String) : RuntimeException(message)

/**
 * Excepción lanzada cuando se viola una regla de negocio del sistema (HTTP 400).
 */
class BusinessRuleException(message: String) : RuntimeException(message)

/**
 * Excepción lanzada cuando lo que el cliente envía es inválido por sí mismo, sin depender del estado del sistema:
 * un archivo ausente, vacío o de un formato que no corresponde (HTTP 400).
 */
class InvalidInputException(message: String) : RuntimeException(message)

/**
 * Excepción lanzada cuando el proveedor de IA falla, no está configurado o devuelve una respuesta inválida (HTTP 503).
 * [retryable] indica si vale la pena reintentar: una llave faltante o rechazada no se arregla repitiendo la llamada.
 * [httpStatus] es el código con que respondió el proveedor, o null si no hubo respuesta (timeout, conexión caída).
 */
class AiUnavailableException(
    message: String,
    cause: Throwable? = null,
    val retryable: Boolean = true,
    val httpStatus: Int? = null,
) : RuntimeException(message, cause)
