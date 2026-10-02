# HTTP API v0.1

Серверный origin не должен иметь префикса пути. JSON UTF-8. Админка использует same-origin HttpOnly cookie `BRSESSION`, срок 8 часов; все изменяющие запросы после login передают `X-CSRF-Token`. Бортовые endpoints используют свой `Authorization: Bearer ...`; сессионная cookie их не авторизует.

| Метод | Путь | Назначение |
|---|---|---|
| GET | /health | Проверка процесса |
| POST | /api/login | `{username:"admin",password:"..."}` → cookie, `{csrf}` |
| GET | /api/me | Текущий CSRF токен |
| POST | /api/logout | Закрыть сессию |
| GET | /api/admin/state | Все редактируемые сущности, бортовые статусы, последние 200 событий, публичный ключ |
| POST | /api/admin/media?name=...&kind=...&rights=... | Тело — байты аудиофайла, НЕ multipart; значения query URL-encoded |
| POST | /api/admin/playlists | Playlist JSON: id, name, slots |
| POST | /api/admin/campaigns | Campaign JSON, см. Model.Campaign |
| POST | /api/admin/routes/import | routes.json формата buscrawl → routes + warnings |
| POST | /api/admin/routes | Один нормализованный Route JSON |
| POST | /api/admin/movement/preview | `{row:{...},vehicle:"..."}` → fix и свежесть |
| POST | /api/admin/buses | `{id,playlistId,routeId}`: регистрация / переназначение |
| POST | /api/admin/buses/rotate-token | `{id}` → новый токен; старый перестаёт работать |
| POST | /api/admin/publish | `{busId}` → revision и число assets |
| GET | /api/bus/{id}/manifest | Подписанный envelope: base64 payload + signature |
| GET | /api/bus/{id}/media/{sha256} | Разрешённый этому борту PCM-объект; HTTP Range поддерживается |
| POST | /api/bus/{id}/events | `{events:[...]}` → `{accepted:[uuid,...]}`, максимум 100 |
| POST | /api/bus/{id}/heartbeat | `{revision,queuedEvents,mode}` |

Новый токен возвращается только при регистрации/ротации, не в /state. Создание автобуса ещё не публикует манифест. Сохранение плейлиста/кампании также не заменяет публикацию.

Пример плейлиста:

```json
{"id":"weekday","name":"Будний день","slots":[
  {"type":"MUSIC","assetId":"song-id","seconds":0,"maxAds":0,"fallbackAssetId":""},
  {"type":"AD_WINDOW","assetId":"","seconds":60,"maxAds":2,"fallbackAssetId":"filler-id"}
]}
```

Нормализованное направление:

```json
{"id":"123:0","name":"123 / прямое","direction":"0","geometry":"SHAPE", "points":[{"lat":55.80,"lon":49.10},{"lat":55.80,"lon":49.12}]}
```

Это лишь пример структуры, не реальная трасса маршрута 123. Отдельно загрузите обратное направление `123:1` с обратной последовательностью настоящих дорожных координат.

Запись события содержит id, playbackId, busId, revision, routeId, at, startedAt, assetId, kind, campaignId, status, completed, frames, fix, output, simulation. Дедупликация идёт по id, не playbackId. Сортировка файлов outbox по UUID не означает порядок воспроизведения; для анализа сопоставляйте playbackId и время, не порядок HTTP-пакета. Последние события в админке упорядочены по приёму сервером.
