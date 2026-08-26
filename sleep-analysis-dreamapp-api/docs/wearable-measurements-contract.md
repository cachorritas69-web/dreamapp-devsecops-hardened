# Contrato: Sincronización de mediciones del wearable

> **Alcance de esta tarea:** este documento describe el contrato del backend
> (`sleep-analysis-dreamapp-api`) para recibir lotes de mediciones del wearable.
> **No se implementó todavía nada en Android** (ni `wear`, ni `appmobile`, ni `dashboardapp`).
> La integración móvil/wearable se realizará en una fase posterior.

---

## 1. Endpoint de carga por lote

| | |
|---|---|
| **URL** | `/sleep/measurements/batch` |
| **Método HTTP** | `POST` |
| **Content-Type** | `application/json` |
| **Autenticación** | Token DreamApp vigente (header `Authorization: Bearer <token>`) o sesión cookie. Rol requerido: `CLIENT`. |
| **Idempotencia** | Sí. Reenviar el mismo lote no crea duplicados. |

### Identidad del usuario

El backend obtiene `userId` **exclusivamente** del token de sesión (`ctx.userInfo.id`).
El JSON **no debe** incluir `userId`, `uidUser`, `username`, `email` ni `correo`
(en ningún nivel de anidamiento); si aparecen, la solicitud se rechaza con HTTP 400.

## 2. Cuerpo de la solicitud

```json
{
  "batchId": "b405fa7d-ea18-4a70-83d8-109d6cbd1e8f",
  "deviceId": "dreamwatch-001",
  "measurements": [
    {
      "clientMeasurementId": "dreamwatch-001-182",
      "measuredAt": "2026-08-25T23:45:10.123Z",
      "heartRateBpm": 62,
      "sleepPhase": "DEEP",
      "hrvRmssd": 45.2,
      "hrvSdnn": 52.1,
      "movement": 0.18
    }
  ]
}
```

### Campos del lote

| Campo | Obligatorio | Tipo | Reglas |
|---|---|---|---|
| `batchId` | Sí | string | UUID válido |
| `deviceId` | Sí | string | 1 a 160 caracteres (se recorta con `trim()`) |
| `measurements` | Sí | array | Entre 1 y 500 elementos |

### Campos de cada medición

| Campo | Obligatorio | Tipo | Reglas |
|---|---|---|---|
| `clientMeasurementId` | Sí | string | 1 a 100 caracteres. Estable, generado por el wearable; habilita la idempotencia. |
| `measuredAt` | Sí | string | Fecha ISO-8601 **con zona horaria** (p. ej. `2026-08-25T23:45:10.123Z`). |
| `heartRateBpm` | Sí | int | Entre 20 y 250. |
| `sleepPhase` | Sí | string | `AWAKE`, `LIGHT`, `DEEP` o `REM`. Se normaliza a mayúsculas en el servidor, pero se recomienda enviarlo ya normalizado. |
| `hrvRmssd` | No | number | Si existe: entre 0 y 1000, finito. |
| `hrvSdnn` | No | number | Si existe: entre 0 y 1000, finito. |
| `movement` | No | number | Si existe: finito y no negativo. |

Se rechazan `NaN` e `Infinity`. Si **una sola** medición es inválida se rechaza
el lote completo (HTTP 400) sin insertar ninguna fila.

## 3. Respuestas

### Éxito (HTTP 200)

```json
{
  "success": true,
  "message": "Mediciones sincronizadas.",
  "data": {
    "batchId": "b405fa7d-ea18-4a70-83d8-109d6cbd1e8f",
    "received": 50,
    "inserted": 48,
    "duplicates": 2
  }
}
```

* `received`: mediciones recibidas en el lote.
* `inserted`: filas realmente creadas.
* `duplicates`: mediciones ignoradas porque ya existían
  (mismo `user_id` + `device_id` + `clientMeasurementId`).

Un reenvío íntegro del mismo lote responde `200` con `inserted: 0` y
`duplicates: N`; es una respuesta correcta, no un error.

### Errores

| Código | Motivo |
|---|---|
| `400` | Lote inválido: JSON malformado, clave prohibida (`userId`, etc.), validación fuera de rango, lote vacío o mayor a 500. |
| `401` | Sin token válido o rol insuficiente. |
| `503` | Error genérico al persistir (sin detalles internos). El cliente debe reintentar más tarde; la operación es transaccional y un fallo hace rollback completo. |

Los errores nunca exponen trazas internas ni mensajes de PostgreSQL.

## 4. Idempotencia

La tabla `sleep_measurement` impone `UNIQUE (user_id, device_id, client_measurement_id)`
y la inserción usa `ON CONFLICT DO NOTHING` dentro de una única transacción:

* El wearable genera un identificador estable por medición.
* Si el celular reintenta el envío (timeout, sin red, crash), las mediciones ya
  guardadas se reportan como `duplicates` y no se duplican datos.

## 5. Límites

* Máximo **500 mediciones** por lote. Para sincronizaciones iniciales grandes,
  partir en varios lotes de hasta 500 y esperar el `200` de cada uno.
* Tamaño máximo de request del servidor: 1 MiB.

## 6. Consulta de mediciones recientes

| | |
|---|---|
| **URL** | `/sleep/measurements/recent?limit=100` |
| **Método HTTP** | `GET` |
| **Autenticación** | Igual que arriba (`Role.CLIENT`). |
| `limit` | Opcional. Entero entre 1 y 500; predeterminado `100`. |

Devuelve las mediciones del usuario autenticado ordenadas por `measured_at DESC`:

```json
{
  "success": true,
  "data": [
    {
      "id": "9f1c3b2a-...",
      "deviceId": "dreamwatch-001",
      "clientMeasurementId": "dreamwatch-001-182",
      "measuredAt": "2026-08-25T23:45:10.123Z",
      "heartRateBpm": 62,
      "sleepPhase": "DEEP",
      "hrvRmssd": 45.2,
      "hrvSdnn": 52.1,
      "movement": 0.18,
      "receivedAt": "2026-08-26T02:00:00.000Z"
    }
  ]
}
```

Nunca es posible consultar mediciones de otro usuario: el filtro por `user_id`
se aplica siempre desde el token, y cualquier parámetro `userId` del query se ignora.

## 7. Ejemplo curl (sin credenciales reales)

```bash
curl -X POST "https://<host-del-backend>/sleep/measurements/batch" \
  -H "Authorization: Bearer $DREAMAPP_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "batchId": "b405fa7d-ea18-4a70-83d8-109d6cbd1e8f",
    "deviceId": "dreamwatch-001",
    "measurements": [
      {
        "clientMeasurementId": "dreamwatch-001-182",
        "measuredAt": "2026-08-25T23:45:10.123Z",
        "heartRateBpm": 62,
        "sleepPhase": "DEEP",
        "hrvRmssd": 45.2,
        "hrvSdnn": 52.1,
        "movement": 0.18
      }
    ]
  }'
```

## 8. Recomendaciones para la integración futura (Android/Wear OS)

Estos puntos son orientativos para la fase posterior; **ninguno está implementado aún**:

1. **El celular debe ser quien envíe el lote** al backend por HTTPS usando el token
   DreamApp existente. El reloj solo entrega las mediciones al teléfono.
2. **Conservar localmente** las mediciones (Room) hasta recibir respuesta `200`;
   eliminarlas (o marcarlas como sincronizadas) solo después de confirmar
   `inserted + duplicates == received`.
3. Ante error de red o `503`, reintentar con backoff reutilizando el mismo
   `batchId` y los mismos `clientMeasurementId`; la idempotencia evita duplicados.
4. Generar `clientMeasurementId` determinista y estable por medición
   (p. ej. `<deviceId>-<timestamp>-<secuencia>`).
5. Enviar `measuredAt` siempre en UTC con zona horaria explícita (`Z`).
6. No incluir jamás identificadores de usuario en el payload.

## 9. Verificación en base de datos (DBeaver)

```sql
SELECT id, user_id, batch_id, client_measurement_id, device_id,
       measured_at, heart_rate_bpm, sleep_phase,
       hrv_rmssd, hrv_sdnn, movement, source, received_at
FROM sleep_measurement
ORDER BY measured_at DESC
LIMIT 200;
```
