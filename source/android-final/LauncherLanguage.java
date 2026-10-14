package org.nhllegacy.port;

import android.app.Activity;
import android.content.Context;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Language for the Android launcher, startup screen, and in-game Android overlays.
 * The original game's own language remains independent. */
final class LauncherLanguage {
    private static final String KEY = "launcher_language";
    private static final Map<String, String> ENGLISH;
    static {
        HashMap<String, String> items = new HashMap<>();
        items.put("Нативный порт для Android\nРелиз 1.0.3", "Native Android port\nRelease 1.0.3");
        items.put("Подготовка графики", "Preparing graphics");
        items.put("Шейдеры сохраняются для следующих запусков.", "Shaders are cached for future launches.");
        items.put("Продолжить игру", "Resume game");
        items.put("Выход в лаунчер", "Exit to launcher");
        items.put("Назначение кнопок", "Button mapping");
        items.put("Расположение кнопок", "Button layout");
        items.put("Игра приостановлена. Настройки кнопок применятся после «Продолжить игру».", "Game paused. Button changes take effect after you select “Resume game”.");
        items.put("Отмена", "Cancel");
        items.put("Настройки игры", "Game settings");
        items.put("Назначение экранных кнопок", "On-screen button mapping");
        items.put("Сохранить", "Save");
        items.put("Сброс", "Reset");
        items.put("Перетащите кнопки · Игра приостановлена", "Drag the buttons · Game paused");
        items.put("Формат экрана", "Display aspect ratio");
        items.put("Исходные пропорции", "Original aspect ratio");
        items.put("Качество графики", "Graphics quality");
        items.put("Производительность — матч 960 × 540", "Performance — match 960 × 540");
        items.put("Чёткость — матч 1280 × 720", "Sharpness — match 1280 × 720");
        items.put("Интерфейс в обоих режимах: 1280 × 720", "Interface in both modes: 1280 × 720");
        items.put("Качество теней", "Shadow quality");
        items.put("Исходные", "Original");
        items.put("Сглаженные", "Filtered");
        items.put("Мягкие", "Soft");
        items.put("Арена", "Arena");
        items.put("Зрители", "Spectators");
        items.put("Трибуны и кресла", "Stands and seats");
        items.put("Запасные на скамейке", "Players on the bench");
        items.put("Экранное управление", "On-screen controls");
        items.put("Выключено", "Off");
        items.put("Авто — без геймпада", "Auto — without a gamepad");
        items.put("Всегда показывать", "Always show");
        items.put("Очистить графический кеш", "Clear graphics cache");
        items.put("Удаляет только скомпилированные шейдеры и кеш Vulkan для этой игры. Сохранения, игровые файлы и установленные драйверы останутся.", "Removes only compiled shaders and the Vulkan cache for this game. Saves, game files, and installed drivers remain intact.");
        items.put("Настройки применяются при новом запуске игры.\n", "Settings take effect the next time the game starts.\n");
        items.put("Полная арена включена по умолчанию. Качество игроков сохраняется.", "The full arena is enabled by default. Player quality stays the same.");
        items.put("Импортировать игровые файлы из ISO", "Import game files from ISO");
        items.put("Сначала полностью закройте игру и дождитесь завершения текущей операции.", "Close the game completely and wait for the current operation to finish.");
        items.put("На устройстве недоступен выбор файлов.", "No file picker is available on this device.");
        items.put("Отменить импорт", "Cancel import");
        items.put("Отмена импорта…", "Cancelling import…");
        items.put("Запустить игру", "Start game");
        items.put("Составы команд", "Team rosters");
        items.put("Выбрать профиль составов", "Choose roster profile");
        items.put("Профиль игры не найден.", "No game profile found.");
        items.put("Профиль для обновления составов", "Profile for roster update");
        items.put("Профиль составов: ", "Roster profile: ");
        items.put(". Теперь выберите ZIP для обновления.", ". Now choose an update ZIP.");
        items.put("Отмена", "Cancel");
        items.put("Обновить состав из ZIP", "Update roster from ZIP");
        items.put("Для импорта полностью закройте игру и дождитесь завершения другой операции.", "Close the game completely and wait for the other operation to finish before importing.");
        items.put("Профиль не найден. Один раз запустите игру и создайте профиль.", "No profile found. Start the game once and create a profile.");
        items.put("Выбор ZIP сразу начинает обновление. Следующий ZIP заменит предыдущий импорт лаунчера для этого профиля; отката нет. Сезоны и другие сохранения не меняются.", "Selecting a ZIP starts the update immediately. The next ZIP replaces the previous launcher import for this profile; there is no rollback. Seasons and other saves are unchanged.");
        items.put("Видеодрайвер Vulkan", "Vulkan graphics driver");
        items.put("Установить драйвер из ZIP", "Install driver from ZIP");
        items.put("Сначала завершите текущую операцию и полностью закройте игру.", "Finish the current operation and close the game completely.");
        items.put("Удалить выбранный драйвер", "Remove selected driver");
        items.put("Проверить выбранный драйвер", "Check selected driver");
        items.put("ZIP AdrenoTools для Android ARM64. Выбирайте версию с поддержкой вашего GPU.\n", "AdrenoTools ZIP for Android ARM64. Choose a version that supports your GPU.\n");
        items.put("Драйвер используется только этой игрой. Изменение применяется при новом запуске.", "The driver is used only by this game. The change takes effect next time the game starts.");
        items.put("Системный драйвер из прошивки устройства.", "System driver from the device firmware.");
        items.put("\nАвтор: ", "\nAuthor: ");
        items.put("Системный (по умолчанию)", "System (default)");
        items.put("Импорт драйвера…", "Importing driver…");
        items.put("Не удалось открыть ZIP", "Could not open ZIP");
        items.put("Драйвер установлен. Выберите его в списке.", "Driver installed. Select it from the list.");
        items.put("Импорт не выполнен: ", "Import failed: ");
        items.put("Предыдущий запуск с внешним драйвером не завершил подготовку. Выбран системный драйвер.", "The previous launch with a custom driver did not finish startup. Switched to the system driver.");
        items.put("Не удалось создать папку настроек", "Could not create settings folder");
        items.put("Не удалось установить настройки игры", "Could not install game settings");
        items.put("Игра уже запущена. Вернитесь в неё или выйдите через BACK → «Выйти в меню приложения».", "The game is already running. Return to it or exit through BACK → “Exit to app menu”.");
        items.put("Файлы игры установлены. Можно использовать экранное управление или геймпад.", "Game files are installed. Use the on-screen controls or a gamepad.");
        items.put("Выберите ISO NHL Legacy Edition кнопкой импорта. Извлекать файлы вручную не нужно.", "Choose an NHL Legacy Edition ISO with the import button. There is no need to extract files manually.");
        items.put("Не удалось подготовить игру: ", "Could not prepare the game: ");
        items.put("Сначала выйдите из игры через BACK → «Выйти в меню приложения»", "Exit the game through BACK → “Exit to app menu” first");
        items.put("Для очистки кеша сначала полностью закройте игру и завершите текущую операцию.", "Close the game completely and finish the current operation before clearing the cache.");
        items.put("Очистить графический кеш?", "Clear graphics cache?");
        items.put("Будут удалены только скомпилированные шейдеры и кеш Vulkan NHL Legacy, включая кеши системного и импортированных драйверов. Сохранения, игровые файлы, настройки и установленные драйверы сохранятся. Первый запуск после очистки может быть медленнее.", "Only compiled shaders and the NHL Legacy Vulkan cache will be removed, including caches for the system and imported drivers. Saves, game files, settings, and installed drivers will remain. The first launch afterward may be slower.");
        items.put("Очистить", "Clear");
        items.put("Очистка графического кеша…", "Clearing graphics cache…");
        items.put("Графический кеш уже пуст.", "The graphics cache is already empty.");
        items.put("Графический кеш очищен: ", "Graphics cache cleared: ");
        items.put(" файлов, ", " files, ");
        items.put(" МБ. Первый запуск может быть медленнее.", " MB. The first launch afterward may be slower.");
        items.put("Кеш не очищен: ", "Could not clear cache: ");
        items.put("Нет доступа к выбранному файлу", "No access to the selected file");
        items.put("Выберите файл NHL Legacy Edition .iso", "Choose an NHL Legacy Edition .iso file");
        items.put("Сначала полностью закройте игру.", "Close the game completely first.");
        items.put("Подготовка импорта… Оставьте приложение открытым.", "Preparing import… Keep the app open.");
        items.put("Неверная таблица файлов", "Invalid file table");
        items.put("Неверное число игровых файлов", "Incorrect number of game files");
        items.put("Проверено и установлено ", "Verified and installed ");
        items.put(" МБ · ", " MB · ");
        items.put(" файлов\nОставьте приложение открытым.", " files\nKeep the app open.");
        items.put("Не удалось открыть ISO", "Could not open ISO");
        items.put("Импорт завершён. Можно запускать игру.", "Import complete. You can start the game.");
        items.put("Импорт не завершён: ", "Import incomplete: ");
        items.put("Проверка и установка ZIP состава… Оставьте лаунчер открытым.", "Checking and installing roster ZIP… Keep the launcher open.");
        items.put("Выбранный профиль больше не найден", "The selected profile is no longer available");
        items.put("Состав обновлён: ", "Roster updated: ");
        items.put(". Он доступен для новых матчей и сезонов после выбора в меню игры. Текущие сохранения не изменены; отката нет.", ". It is available for new games and seasons after you select it in the game menu. Existing saves are unchanged; there is no rollback.");
        items.put("Обновление не выполнено: ", "Update failed: ");
        items.put("Сначала завершите импорт и полностью закройте игру.", "Finish the import and close the game completely.");
        items.put("Проверяем выбранный видеодрайвер…", "Checking the selected graphics driver…");
        items.put("ХОККЕЙ НА ТВОЁМ УСТРОЙСТВЕ", "HOCKEY ON YOUR DEVICE");
        items.put("▶   ИГРАТЬ", "▶   PLAY");
        items.put("⚙  Настройки", "⚙  Settings");
        items.put("⇩  Импорт ISO", "⇩  Import ISO");
        items.put("Импортируйте ISO NHL Legacy Edition для первого запуска. Выберите качество матча и видеодрайвер в настройках. Изменения применятся после нового запуска игры. Графический кеш можно очистить там же; сохранения не затрагиваются.", "Import your NHL Legacy Edition ISO before the first launch. Choose match quality and a graphics driver in Settings. Changes take effect the next time the game starts. You can also clear the graphics cache there without affecting saves.");
        items.put("Понятно", "Got it");
        items.put("Настройки", "Settings");
        items.put("✓  Настройки сохранены", "✓  Settings saved");
        items.put("Готово", "Done");
        items.put("Переключить язык", "Switch language");
        ENGLISH = Collections.unmodifiableMap(items);
    }

    private static final String[][] DETAILS = new String[][] {
        {"Поддерживается только Xbox RosterFile; PS3 SYS-DATA не устанавливается", "Only Xbox RosterFile is supported; PS3 SYS-DATA cannot be installed"},
        {"Процесс проверки завершился аварийно. Попробуйте системный драйвер.", "Driver check process crashed. Try the system driver."},
        {"\nБазовая проверка пройдена. Изображение и FPS проверяются в матче.", "\nBasic check passed. Check the image and FPS in a match."},
        {"Нужен драйвер Android ARM64 (.so), совместимый с AdrenoTools", "An Android ARM64 driver (.so) compatible with AdrenoTools is required"},
        {"Не удалось завершить импорт; прежняя игра восстановлена", "Could not finish import; the previous game was restored"},
        {"Файлы драйвера повреждены; импортируйте ZIP заново", "Driver files are damaged; import the ZIP again"},
        {"Имя файла состава не совпадает с описанием пакета", "Roster filename does not match the package manifest"},
        {"Это не ZIP AdrenoTools: отсутствует meta.json", "This is not an AdrenoTools ZIP: meta.json is missing"},
        {"Старый состав содержит дополнительные файлы: ", "Old roster contains extra files: "},
        {"Файлы драйвера находятся вне папки meta.json", "Driver files are outside the meta.json folder"},
        {"Проверка драйвера не ответила за 20 секунд.", "Driver check did not respond within 20 seconds."},
        {"Драйвер вернул неполный результат проверки.", "Driver check returned an incomplete result."},
        {"Не удалось удалить временный файл драйвера", "Could not delete temporary driver file"},
        {"Не удалось связаться с проверкой драйвера.", "Could not contact driver checker."},
        {"Не удалось восстановить прежние файлы игры", "Could not restore previous game files"},
        {"Импорт отменён. Прежние файлы сохранены.", "Import cancelled. Previous files were preserved."},
        {"Не удалось запустить проверку драйвера.", "Could not start driver check."},
        {"Не удалось создать папку игрового файла", "Could not create game-file folder"},
        {"Контрольная сумма состава не совпадает", "Roster checksum does not match"},
        {"Ошибка проверки установленного состава", "Installed roster failed verification"},
        {"Распакованный драйвер слишком большой", "Extracted driver is too large"},
        {"Удаление за пределами папки драйверов", "Deletion attempted outside driver folder"},
        {"Слишком длинные пути файлов драйвера", "Driver file paths are too long"},
        {"Не удалось завершить импорт драйвера", "Could not finish driver import"},
        {"Не удалось прочитать временную папку", "Could not read temporary folder"},
        {"Отсутствует или повреждён meta.json", "meta.json is missing or damaged"},
        {"Размер внутренней базы не совпадает", "Internal database size does not match"},
        {"Не удалось удалить прежний состав: ", "Could not delete old roster: "},
        {"Идёт другая операция с файлами игры", "Another game-file operation is running"},
        {"Ошибка проверки записанного состава", "Written roster failed verification"},
        {"Не удалось создать папку драйверов", "Could not create driver folder"},
        {"Распакованный состав слишком велик", "Extracted roster is too large"},
        {"Не удалось подготовить замену игры", "Could not prepare game replacement"},
        {"Слишком длинное название драйвера", "Driver name is too long"},
        {"Неподдерживаемая версия meta.json", "Unsupported meta.json version"},
        {"Неизвестный формат пакета состава", "Unknown roster package format"},
        {"Неверная структура таблиц состава", "Invalid roster table structure"},
        {"Не удалось удалить временный файл", "Could not delete temporary file"},
        {"Неверное имя библиотеки драйвера", "Invalid driver library name"},
        {"Не удалось создать папку состава", "Could not create roster folder"},
        {"Название состава слишком длинное", "Roster title is too long"},
        {"Неверный комплект игровых файлов", "Incorrect game-file set"},
        {"Не удалось создать папку импорта", "Could not create import folder"},
        {"Неверная таблица игровых файлов", "Invalid game-file table"},
        {"Недопустимое название драйвера", "Invalid driver name"},
        {"Путь выходит из папки драйвера", "Path leaves the driver folder"},
        {"Недостаточно места: нужно ещё ", "Not enough space: need another "},
        {"Повреждён игровой файл в ISO: ", "Damaged game file in ISO: "},
        {"Не удалось подготовить импорт", "Could not prepare import"},
        {"В ZIP должен быть один состав", "ZIP must contain one roster"},
        {"Неверное имя или дата состава", "Invalid roster name or date"},
        {"Не удалось прочитать кеш игры", "Could not read game cache"},
        {"Не удалось сохранить кеш игры", "Could not preserve game cache"},
        {"Неверный размер файла в ISO: ", "Incorrect file size in ISO: "},
        {"Другой импорт ещё выполняется", "Another import is already running"},
        {"Не указано название драйвера", "Driver name is missing"},
        {"Драйвер требует Android API ", "Driver requires Android API "},
        {"Зарезервированное имя файла", "Reserved filename"},
        {"Неполный комплект драйвера", "Incomplete driver package"},
        {"ZIP драйвера больше 128 МБ", "Driver ZIP exceeds 128 MB"},
        {"Слишком много файлов в ZIP", "Too many files in ZIP"},
        {"Файл состава слишком велик", "Roster file is too large"},
        {"В ZIP слишком много файлов", "Too many files in ZIP"},
        {"В ZIP не должно быть папок", "ZIP must not contain folders"},
        {"Повреждённый ZIP драйвера", "Damaged driver ZIP"},
        {"Недопустимый путь к файлу", "Invalid file path"},
        {"Повторяющийся путь в ZIP", "Duplicate path in ZIP"},
        {"Не удалось создать папку", "Could not create folder"},
        {"Повторная таблица пакета", "Duplicate package manifest"},
        {"Недопустимый путь в ZIP", "Invalid path in ZIP"},
        {"Неоднозначный meta.json", "Ambiguous meta.json"},
        {"В ZIP нет файла состава", "ZIP contains no roster file"},
        {"Неверный размер состава", "Invalid roster size"},
        {"Повреждён сжатый состав", "Compressed roster is damaged"},
        {"Недопустимое имя в ZIP", "Invalid name in ZIP"},
        {"Профиль игры не найден", "Game profile not found"},
        {"Проверка не завершена.", "Check did not finish."},
        {"Неверная база состава", "Invalid roster database"},
        {"Не удалось удалить: ", "Could not delete: "},
        {"Неполный импорт ISO", "Incomplete ISO import"},
        {"Проверка отменена.", "Check cancelled."},
        {"Драйвер не выбран", "No driver selected"},
        {" МБ для установки", " MB to install"},
        {", устройство: ", ", device: "},
        {"не указана", "not specified"},
        {"нет", "no"},
        {"да", "yes"},
        {"Папка приложения недоступна", "App folder is unavailable"},
        {"Импорт файлов ещё выполняется", "A file import is still running"},
        {"Путь графического кеша недостоверен", "Graphics cache path is invalid"},
        {"Графический кеш вне каталога приложения", "Graphics cache is outside the app folder"},
    };

    static boolean isEnglish(Activity activity) {
        return "en".equals(activity.getPreferences(Context.MODE_PRIVATE).getString(KEY, "ru"));
    }

    static String text(Activity activity, String russian) {
        return text(isEnglish(activity), russian);
    }

    static String text(boolean english, String russian) {
        if (!english) return russian;
        String translated = ENGLISH.get(russian);
        return translated != null ? translated : russian;
    }

    static String detail(Activity activity, String message) {
        if (!isEnglish(activity) || message == null) return message;
        for (String[] pair : DETAILS) {
            if (pair[0].length() > 4) message = message.replace(pair[0], pair[1]);
        }
        return message.replace(": да", ": yes").replace(": нет", ": no");
    }

    static void toggle(Activity activity) {
        String next = isEnglish(activity) ? "ru" : "en";
        if (!activity.getPreferences(Context.MODE_PRIVATE).edit().putString(KEY, next).commit())
            throw new IllegalStateException("Could not save launcher language");
    }
}
