# Digital

Современный русский форк [Digital](https://github.com/hneemann/Digital) на основе
[nicoladen05/Digital](https://github.com/nicoladen05/Digital). Симулятор, формат `.dig`,
библиотека компонентов и примеры остаются частью одного приложения.

- Светлая и тёмная темы FlatLaf, масштабирование и меню macOS.
- Полный актуальный русский интерфейс, справка по компонентам и редактор конечных автоматов.
- Настраиваемые горячие клавиши компонентов без конфликтов с редактором.
- Приложение для macOS со встроенной Java: внешняя Java для запуска не требуется.
- Открытие `.dig` и `.fsm` из Finder, защита несохранённых изменений при выходе.

## Установка на macOS

Для сборки нужны JDK 21+ и Maven. Для установленного приложения эти инструменты не нужны.

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
distribution/macos/build.sh
distribution/macos/install.sh
```

Приложение устанавливается в `/Applications/Digital.app`. Установщик также принимает
абсолютный путь, например `$HOME/Applications/Digital.app`. Если Maven находится
в другом месте, задайте `MAVEN_CMD`.

Русский язык включён по умолчанию в macOS-пакете. Язык и тему можно изменить
в настройках приложения. Библиотека и примеры входят в пакет:
`Digital.app/Contents/app/lib` и `Digital.app/Contents/app/examples`.

## Разработка и проверка

```sh
mvn -Dtest=TestLang,TestRussianLanguage,KeybindManagerTest -Djacoco.skip=true test
mvn -DskipTests -Djacoco.skip=true package
distribution/macos/build.sh --skip-build
```

Сборка создаёт `target/Digital.jar` и `target/macos/Digital.app`. Код запуска находится
в `de.neemann.digital.Main`, нативные события macOS подключает `gui.DesktopIntegration`,
переводы находятся в `src/main/resources/lang`, библиотека и примеры — в `src/main/dig`.

Для проверки интерфейса: создайте схему И из двух входов и одного выхода, запустите
симуляцию, измените входы, сохраните схему и откройте её из Finder. В настройках
проверьте смену темы и сохранение горячих клавиш.

## Авторы и лицензия

Оригинальный Digital — Helmut Neemann и участники проекта. Обновление интерфейса —
[aidan4/Digital](https://github.com/aidan4/Digital) и nicoladen05. Русский каталог основан
на [переводе technokratos](https://github.com/hneemann/Digital/pull/735), обновлён и исправлен
для этой версии. Значки оригинального проекта сохранены.

Лицензия [GPL-3.0](LICENSE). Это неофициальный форк.
