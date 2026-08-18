# Registro y verificación por correo

DreamApp crea cuentas personales con el rol `Cliente`. La cuenta se guarda definitivamente solo después de validar el código de seis dígitos enviado por correo.

## Variables de entorno en Render

Configura estas variables únicamente en el servicio `dreamapp-api`:

- `SMTP_USERNAME`: dirección de la cuenta de Google que enviará los códigos.
- `SMTP_APP_PASSWORD`: contraseña de aplicación de Google de 16 caracteres (no es la contraseña normal de Google).
- `SMTP_FROM`: normalmente la misma dirección de `SMTP_USERNAME`.
- `EMAIL_VERIFICATION_SECRET`: secreto aleatorio de 32 caracteres o más. El Blueprint puede generarlo.

La cuenta de Google debe tener activada la verificación en dos pasos. Después crea una contraseña de aplicación para DreamApp y cópiala en `SMTP_APP_PASSWORD`.

## Comportamiento de seguridad

- Los códigos caducan en 10 minutos.
- Se permiten cinco intentos por código.
- Solo se puede solicitar un código por correo cada 60 segundos.
- Se almacena el hash del código, nunca el código original.
- Las contraseñas se almacenan con BCrypt.
- Las rutas de métricas e IA siempre usan el identificador de la sesión autenticada e ignoran identificadores enviados por el navegador.
- El antiguo usuario bootstrap `admin` se elimina durante la migración y ya no se vuelve a crear.
