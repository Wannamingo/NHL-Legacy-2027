## NHL Legacy 2027 — 1.0.3

Выбранный в лаунчере язык RU/EN теперь используется на экране подготовки графики и в Android-настройках во время игры: меню Select, настройках кнопок, окне назначения и режиме перемещения кнопок. Лаунчер переключается сразу, внутриигровые экраны — при следующем запуске игры. Язык оригинального игрового интерфейса выбирается отдельно.

The launcher’s RU/EN choice now carries into graphics preparation and in-game Android controls: the Select menu, game settings, button mapping, and button-layout editor. The launcher updates immediately; in-game Android screens use the choice on the next game launch. The original game language remains separate.

Signed Android ARM64 APK, `versionCode 14`, same package and signing certificate as 1.0.2. Native libraries, game assets, and the ISO importer are unchanged byte for byte. The Y700 installed this version over 1.0.2 without deleting imported game files. Device checks confirmed the graphics-preparation screen and game settings in both EN and RU, the English button-mapping and layout screens, and both Select-menu languages. A match was not replayed for this text-only update.

---

## NHL Legacy 2027 — 1.0.2

Лаунчер теперь поддерживает русский и английский языки. Кнопка **RU / EN** в правом верхнем углу переключает текст сразу, сохраняет выбор для следующих запусков и работает независимо от языка самой игры. Переведены главный экран, настройки, импорт ISO, составы, видеодрайверы и сообщения об ошибках. Справка первого запуска исправлена: игровые данные импортируются из ISO.

The launcher now supports Russian and English. Tap **RU / EN** in the upper-right corner to switch immediately; the choice persists across restarts and does not change the game's own language. The home screen, settings, ISO and roster imports, graphics-driver controls, and common diagnostics are translated. The first-launch help correctly refers to ISO import.

Signed Android ARM64 APK; `versionCode 13`, same package and signing certificate as 1.0.1. The game libraries, assets, and ISO importer are unchanged byte for byte. Host checks covered the language preference, both toggle directions, UI labels, diagnostic translation, APK signing, ZIP integrity, and 16 KB native-library alignment. On Lenovo Y700, the APK installed over 1.0.1 without removing game files. EN → RU → EN changed the visible launcher text immediately; EN persisted after force-stop and reopening. The English help correctly describes ISO import. A new match was not run for this language-only update; the successful full ISO import was tested on 1.0.1.

The existing limitations remain: full-game completion and all GPU/driver combinations are untested; FPS may drop in demanding views, and goalkeeper pose errors have appeared in replays. Bring your own original NHL Legacy Edition ISO; game files are not included.

---

## NHL Legacy 2027 — 1.0.1

### Русский

- Первый публичный APK для Android ARM64 с лаунчером NHL Legacy 2027.
- Игровые файлы импортируются **только из ISO**. Проверяются 20 файлов по размеру и SHA-256; ZIP игровых данных больше не поддерживается. ZIP составов и драйверов остаются отдельными функциями.
- Два режима матча: 960 × 540 и 1280 × 720, интерфейс 1280 × 720. Доступны настройки полной арены, управления, системного или пользовательского Vulkan-драйвера и очистка графического кеша.
- При ошибке либо отмене импорта прежняя установка сохраняется. Успешное обновление сохраняет кеши и пользовательские данные.

Полный импорт ISO подтверждён пользователем на Lenovo Y700 (`TB321FU`, Android 15); все 20 файлов дополнительно проверены независимым чтением. Полное прохождение и совместимость со всеми GPU не подтверждены. Возможны просадки FPS и неверная поза вратаря в отдельных повторах.

Для игры требуется собственный оригинальный ISO NHL Legacy Edition. Образ и игровые данные здесь не распространяются. После импорта ISO может быть удалён с устройства, поскольку нужные файлы установлены в хранилище приложения.

### English

- First public Android ARM64 APK with the NHL Legacy 2027 launcher.
- **ISO-only game-data import** verifies 20 files by size and SHA-256. Game-data ZIP import is removed; roster and driver ZIP imports remain separate.
- Match resolution choices of 960 × 540 and 1280 × 720, with a 1280 × 720 UI; arena visibility, controls, Vulkan driver selection, and graphics-cache clearing.
- Import failure or cancellation leaves the prior installation intact; a successful replacement preserves cache and user data.

The user completed an ISO import on Lenovo Y700 (`TB321FU`, Android 15), and all 20 files passed an independent reader check. Full-game completion and all GPU combinations remain untested. Demanding scenes can lose FPS, and goalkeeper pose errors have previously appeared in some replays.

Bring your own original NHL Legacy Edition ISO. No image or game data is distributed here. The ISO may be removed after import because the required files are installed into app storage.
