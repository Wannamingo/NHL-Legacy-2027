# Статус исходных материалов / Source status

Этот каталог содержит базу порта, актуальные Java-классы Android-лаунчера, тесты ISO и справочные native-фрагменты. Он не является полным воспроизводимым исходным деревом релизного APK. Игровые функции генерируются из собственной копии игры, а поздние native-изменения представлены только частично. Скрипты сборки в port-base описывают более раннее состояние.

The directory contains the port base, current Android Java classes, ISO tests, and native reference fragments. It is not a complete reproducible source tree for this APK. Game functions are generated from the user's own game copy; some late native changes are only retained as reference fragments.

`android-final/` contains launcher settings, AdrenoTools integration, the ISO-only game-data importer and the in-game control overlay. `tests/` contains host probes for XDVDFS reading and transactional import. `port-base/` and `native-release-reference/` preserve earlier and reference work.

ISO/XEX, extracted resources, generated game code, saves, GPU traces, APK and signing key are intentionally excluded. See the repository README and THIRD_PARTY_NOTICES.md for scope and provenance.
