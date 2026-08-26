package team.dreamapp.com.presentation.documentation

import io.javalin.http.Context

object OpenApiDocumentation {
    private val jsonContent = mapOf("application/json" to mapOf("schema" to mapOf("type" to "object")))
    private fun response(description: String) = mapOf("description" to description, "content" to jsonContent)
    private fun operation(
        summary: String,
        tag: String,
        protected: Boolean = true,
        bodySchema: Map<String, Any>? = null,
        parameters: List<Map<String, Any>> = emptyList(),
        success: String = "Operación exitosa"
    ): Map<String, Any> = buildMap {
        put("summary", summary)
        put("tags", listOf(tag))
        if (protected) put("security", listOf(mapOf("bearerAuth" to emptyList<String>()), mapOf("cookieAuth" to emptyList<String>())))
        if (parameters.isNotEmpty()) put("parameters", parameters)
        if (bodySchema != null) put("requestBody", mapOf(
            "required" to true,
            "content" to mapOf("application/json" to mapOf("schema" to bodySchema))
        ))
        put("responses", buildMap {
            put("200", response(success))
            put("400", response("Solicitud inválida"))
            if (protected) put("401", response("No autenticado"))
            put("429", response("Demasiadas solicitudes"))
        })
    }

    private fun obj(vararg properties: Pair<String, String>, required: List<String> = properties.map { it.first }) = mapOf(
        "type" to "object",
        "required" to required,
        "properties" to properties.associate { (name, format) ->
            name to if (format == "email") mapOf("type" to "string", "format" to "email") else mapOf("type" to format)
        }
    )

    private fun pathParameter(name: String) = mapOf<String, Any>(
        "name" to name, "in" to "path", "required" to true, "schema" to mapOf("type" to "string")
    )

    fun spec(ctx: Context): Map<String, Any> {
        val serverUrl = "${ctx.scheme()}://${ctx.host()}"
        return mapOf(
            "openapi" to "3.0.3",
            "info" to mapOf(
                "title" to "DreamApp API",
                "version" to "1.0.0",
                "description" to "API para cuentas personales, métricas de sueño, análisis con IA y suscripciones."
            ),
            "servers" to listOf(mapOf("url" to serverUrl)),
            "tags" to listOf("Estado", "Autenticación", "Cuentas", "Usuarios", "Sueño", "IA", "Suscripción")
                .map { mapOf("name" to it) },
            "paths" to mapOf(
                "/" to mapOf("get" to operation("Información básica de la API", "Estado", false)),
                "/health" to mapOf("get" to operation("Comprobar disponibilidad", "Estado", false)),
                "/auth/login" to mapOf("post" to operation(
                    "Iniciar sesión", "Autenticación", false,
                    obj("userName" to "string", "password" to "string")
                )),
                "/auth/google" to mapOf("post" to operation(
                    "Vincular o crear una cuenta mediante un token Firebase de Google", "Autenticación", true,
                    success = "Cuenta vinculada y sesión creada"
                )),
                "/auth/register" to mapOf("post" to operation(
                    "Solicitar registro y código de verificación", "Autenticación", false,
                    obj("firstName" to "string", "lastName" to "string", "userName" to "string", "email" to "email", "password" to "string"),
                    success = "Código de verificación enviado"
                )),
                "/auth/verify" to mapOf("post" to operation(
                    "Confirmar correo y crear la cuenta", "Autenticación", false,
                    obj("email" to "email", "code" to "string"), success = "Cuenta creada"
                )),
                "/auth/logout" to mapOf("post" to operation("Cerrar sesión", "Autenticación")),
                "/account" to mapOf(
                    "get" to operation("Listar cuentas (administración)", "Cuentas"),
                    "post" to operation("Crear una cuenta (administración)", "Cuentas", bodySchema = mapOf("type" to "object"))
                ),
                "/account/{id}" to mapOf(
                    "get" to operation("Consultar una cuenta", "Cuentas", parameters = listOf(pathParameter("id"))),
                    "patch" to operation("Actualizar una cuenta", "Cuentas", bodySchema = mapOf("type" to "object"), parameters = listOf(pathParameter("id"))),
                    "delete" to operation("Eliminar una cuenta", "Cuentas", parameters = listOf(pathParameter("id")))
                ),
                "/account/userinfo/{username}" to mapOf("get" to operation(
                    "Consultar cuenta por usuario", "Cuentas", parameters = listOf(pathParameter("username"))
                )),
                "/users" to mapOf("get" to operation("Listar usuarios sincronizados", "Usuarios")),
                "/users/notify-update" to mapOf("post" to operation("Notificar actualización de usuarios", "Usuarios")),
                "/sleep/stats" to mapOf("get" to operation("Obtener las métricas de sueño del usuario actual", "Sueño")),
                "/sleep/sessions" to mapOf("post" to operation(
                    "Registrar o actualizar una sesión de sueño del usuario actual", "Sueño",
                    bodySchema = mapOf("type" to "object")
                )),
                "/sleep/states" to mapOf(
                    "get" to operation("Consultar estados actuales de sueño", "Sueño"),
                    "post" to operation("Registrar un cambio de estado de sueño", "Sueño", bodySchema = mapOf("type" to "object"))
                ),
                "/sleep/connections" to mapOf("get" to operation("Consultar conexiones del monitor", "Sueño")),
                "/sleep/measurements/batch" to mapOf("post" to operation(
                    "Sincronizar un lote de mediciones del wearable (idempotente)", "Sueño",
                    bodySchema = mapOf(
                        "type" to "object",
                        "required" to listOf("batchId", "deviceId", "measurements"),
                        "properties" to mapOf(
                            "batchId" to mapOf("type" to "string", "format" to "uuid"),
                            "deviceId" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 160),
                            "measurements" to mapOf(
                                "type" to "array", "minItems" to 1, "maxItems" to 500,
                                "items" to mapOf(
                                    "type" to "object",
                                    "required" to listOf("clientMeasurementId", "measuredAt", "heartRateBpm", "sleepPhase"),
                                    "properties" to mapOf(
                                        "clientMeasurementId" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 100),
                                        "measuredAt" to mapOf("type" to "string", "format" to "date-time"),
                                        "heartRateBpm" to mapOf("type" to "integer", "minimum" to 20, "maximum" to 250),
                                        "sleepPhase" to mapOf("type" to "string", "enum" to listOf("AWAKE", "LIGHT", "DEEP", "REM")),
                                        "hrvRmssd" to mapOf("type" to "number", "minimum" to 0, "maximum" to 1000),
                                        "hrvSdnn" to mapOf("type" to "number", "minimum" to 0, "maximum" to 1000),
                                        "movement" to mapOf("type" to "number", "minimum" to 0)
                                    )
                                )
                            )
                        )
                    ),
                    success = "Mediciones sincronizadas."
                )),
                "/sleep/measurements/recent" to mapOf("get" to operation(
                    "Consultar las mediciones recientes del usuario actual", "Sueño",
                    parameters = listOf(mapOf<String, Any>(
                        "name" to "limit", "in" to "query", "required" to false,
                        "schema" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 500, "default" to 100)
                    ))
                )),
                "/ai/recommendation" to mapOf("get" to operation("Generar recomendación personalizada", "IA")),
                "/ai/predictions-next-month-efficiency" to mapOf("get" to operation("Predecir eficiencia del próximo mes", "IA")),
                "/subscription" to mapOf(
                    "get" to operation("Consultar el plan actual", "Suscripción"),
                    "patch" to operation(
                        "Cambiar el plan", "Suscripción",
                        bodySchema = mapOf(
                            "type" to "object", "required" to listOf("plan"),
                            "properties" to mapOf("plan" to mapOf("type" to "string", "enum" to listOf("FREE", "PLUS", "PRO")))
                        )
                    )
                )
            ),
            "components" to mapOf(
                "securitySchemes" to mapOf(
                    "bearerAuth" to mapOf("type" to "http", "scheme" to "bearer", "bearerFormat" to "token"),
                    "cookieAuth" to mapOf("type" to "apiKey", "in" to "cookie", "name" to "JSESSIONID")
                )
            )
        )
    }

    val swaggerHtml = """
        <!doctype html>
        <html lang="es">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width,initial-scale=1">
          <title>DreamApp API · Swagger</title>
          <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/swagger-ui-dist@5/swagger-ui.css">
          <style>body{margin:0;background:#f4f7fb}.topbar{display:none}</style>
        </head>
        <body>
          <div id="swagger-ui"></div>
          <script src="https://cdn.jsdelivr.net/npm/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
          <script>
            window.onload = () => SwaggerUIBundle({
              url: '/openapi.json', dom_id: '#swagger-ui', deepLinking: true,
              persistAuthorization: false, displayRequestDuration: true,
              tryItOutEnabled: false
            });
          </script>
        </body>
        </html>
    """.trimIndent()
}
