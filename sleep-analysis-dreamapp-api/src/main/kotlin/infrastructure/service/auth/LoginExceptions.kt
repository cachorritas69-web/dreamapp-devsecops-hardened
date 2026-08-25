package team.dreamapp.com.infrastructure.service.auth

/**
 * Typed login failures. Every credential-related failure is surfaced publicly
 * as the same generic 401 response to prevent user enumeration; only unexpected
 * errors (database, programming bugs) become 500 without internal details.
 */
class InvalidCredentialsException : Exception("Invalid credentials")
class AccountInactiveException : Exception("The user is not active")
class RoleNotAllowedException : Exception("The user does not have this role")
