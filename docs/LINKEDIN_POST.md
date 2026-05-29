# LinkedIn Posts for ComposePreviewPro

Three ready-to-publish drafts: **English**, **Russian**, **Uzbek**.
Copy-paste any one — they share the same technical achievement framing, only the wording differs.

Replace `https://plugins.jetbrains.com/plugin/com.composepreviewpro` with the actual marketplace URL once the listing goes live.

---

## English

🚀 **Shipped: ComposePreviewPro 0.3.7 — device-less Jetpack / Compose Multiplatform preview, inside IntelliJ IDEA & Android Studio.**

After months of "click ▶ → wait for the emulator → realise the preview is wrong → repeat" I built the plugin I always wanted.

**What it does**
• Renders any `@Composable` to a PNG without booting an emulator or running the app.
• Auto-mocks every argument — primitives, data classes, sealed hierarchies, `Modifier`, function types, `Flow`, `ViewModel`, custom `CompositionLocal`s — so you don't need preview-only overloads anymore.
• Hot-reloads in ~51 ms via JVM `Instrumentation.redefineClasses`. State, scroll position, and animations survive the swap.
• Click an element on the canvas → its source-call arguments appear in an editable side panel. Edits write back via PSI (formatting, comments, trailing commas all preserved).
• Click-to-source navigation with a Composable-vs-modifier ranker that lands on the user-visible callee, not `fillMaxSize`.
• Multi-frame device grid, interactive preview, native ComposePanel "live UI" mode, and an optional AI mock-strings toggle.

**What I learned shipping it**
• Out-of-process renderer over NDJSON-on-stdio scales better than embedding Compose in the IDE classloader (no Skiko / JBR clashes).
• Plugin Marketplace verifier got noticeably stricter in 2026.2 — every `@ApiStatus.Internal` and Kotlin-generated bridge to a deprecated platform method is now a publish-blocker.
• `jvm-default=NO_COMPATIBILITY` is the right answer when you implement a Java interface in Kotlin and don't want synthetic bridges.
• Constructor-injected `CoroutineScope` (IJPL-83) makes service lifecycle trivial — no more manual `SupervisorJob().cancel()` ceremonies.
• JEP 451 is real — self-attach for JVM agents is deprecated; `-javaagent:` at JVM start is the long-term path.

**Where to get it**
🔗 GitHub: https://github.com/komzakdroid/ComposePreviewPro
🔗 JetBrains Marketplace: https://plugins.jetbrains.com/plugin/com.composepreviewpro
🔗 Issues / feature requests welcome.

If you're tired of the emulator-on-the-critical-path render cycle, give it a try and let me know what breaks — every bug report makes the next version more robust.

#JetpackCompose #ComposeMultiplatform #Kotlin #Android #AndroidDevelopment #IntelliJIDEA #AndroidStudio #DeveloperTools #OpenSource #JetBrainsPlugin

---

## Русский

🚀 **Выпустил: ComposePreviewPro 0.3.7 — превью Jetpack / Compose Multiplatform без эмулятора, прямо в IntelliJ IDEA и Android Studio.**

После месяцев "нажми ▶ → жди эмулятор → понимаешь, что превью неправильное → повтори" я собрал плагин, которого мне самому давно не хватало.

**Что он умеет**
• Рендерит любой `@Composable` в PNG без запуска эмулятора и без сборки приложения.
• Автоматически мокирует любые аргументы — примитивы, data class, sealed-иерархии, `Modifier`, function types, `Flow`, `ViewModel`, пользовательские `CompositionLocal` — превью-only перегрузки больше не нужны.
• Hot reload за ~51 мс через JVM `Instrumentation.redefineClasses`. State, позиция прокрутки, анимации — переживают подмену байткода.
• Клик по элементу на канвасе → его аргументы появляются в редактируемой боковой панели. Правки уходят обратно в исходник через PSI (форматирование, комментарии, trailing commas сохраняются).
• Click-to-source с двухуровневым ранкером, который ведёт к видимому пользователю Composable, а не к `fillMaxSize`.
• Сетка устройств (телефон + планшет + десктоп), интерактивный режим, нативный `ComposePanel` для "live UI", опциональный AI-мок строк через Claude.

**Что я понял по дороге**
• Рендер в отдельном процессе через NDJSON-stdio масштабируется лучше, чем встраивание Compose в classloader IDE (никаких конфликтов Skiko / JBR).
• Marketplace verifier в 2026.2 стал заметно строже — каждый `@ApiStatus.Internal` и каждый Kotlin-сгенерированный bridge к deprecated-методу платформы теперь блокирует публикацию.
• `jvm-default=NO_COMPATIBILITY` — правильный ответ, когда реализуешь Java-интерфейс в Kotlin и не хочешь synthetic bridges.
• Constructor-injection `CoroutineScope` (IJPL-83) убивает половину boilerplate в service lifecycle.
• JEP 451 не миф — self-attach для JVM-агентов deprecated; `-javaagent:` на старте JVM — долгосрочный путь.

**Где взять**
🔗 GitHub: https://github.com/komzakdroid/ComposePreviewPro
🔗 JetBrains Marketplace: https://plugins.jetbrains.com/plugin/com.composepreviewpro
🔗 Баг-репорты и фичреквесты приветствуются.

Если устали от цикла "эмулятор-на-критическом-пути", попробуйте — и расскажите, что ломается. Каждый баг-репорт делает следующий релиз надёжнее.

#JetpackCompose #ComposeMultiplatform #Kotlin #Android #AndroidDev #IntelliJIDEA #AndroidStudio #DevTools #OpenSource #JetBrainsPlugin

---

## O'zbek

🚀 **Chiqdi: ComposePreviewPro 0.3.7 — Jetpack / Compose Multiplatform uchun emulatorsiz preview, to'g'ridan-to'g'ri IntelliJ IDEA va Android Studio ichida.**

Oylar davomida "▶ bos → emulatorni kut → preview noto'g'ri ekanini ko'r → qaytadan" tsiklidan keyin men o'zim doim xohlagan plugin'ni qurib chiqdim.

**Plugin nima qiladi**
• Har qanday `@Composable` funksiyani PNG'ga render qiladi — emulator yoqilmasdan, ilova ishga tushmasdan.
• Har qanday argumentni avtomatik mock qiladi: primitivlar, data class, sealed iyerarxiyalar, `Modifier`, function types, `Flow`, `ViewModel`, foydalanuvchi `CompositionLocal`lari. Endi "faqat preview uchun" overload yozmaysiz.
• Hot reload ~51 ms — JVM `Instrumentation.redefineClasses` orqali. State, scroll holati, animatsiyalar bytecode swap'idan keyin saqlanadi.
• Canvas'dagi elementni bossangiz, uning source argumentlari yon paneldan editable bo'lib chiqadi. PSI orqali source'ga qaytariladi (format, comment, trailing comma — hammasi saqlanadi).
• Click-to-source — Composable callee'ni `fillMaxSize` kabi modifier'dan farqlovchi ikki bosqichli ranker bilan.
• Multi-frame device grid (telefon + planshet + desktop), interactive mode, ichki `ComposePanel` "live UI" rejimi, va Claude API orqali AI-yordamli string mock'lar.

**Ushbu loyihada o'rgangan asosiy darslarim**
• Out-of-process renderer + NDJSON-stdio IPC — Compose'ni IDE classloader'iga embed qilishdan ko'ra ancha barqaror (Skiko / JBR clash'lar yo'q).
• 2026.2 Marketplace verifier ancha qattiqlashdi — har bir `@ApiStatus.Internal` va Kotlin-generated bridge to deprecated platform method publish'ni bloklaydi.
• `jvm-default=NO_COMPATIBILITY` — Kotlin'da Java interface implement qilganda synthetic bridge'lardan qutilishning to'g'ri yo'li.
• Constructor-injected `CoroutineScope` (IJPL-83) — service lifecycle boilerplate'ining yarmini olib tashlaydi.
• JEP 451 jiddiy — JVM agent self-attach deprecated; `-javaagent:` JVM startida — uzoq muddatli yo'l.

**Qayerdan olish mumkin**
🔗 GitHub: https://github.com/komzakdroid/ComposePreviewPro
🔗 JetBrains Marketplace: https://plugins.jetbrains.com/plugin/com.composepreviewpro
🔗 Bug report va feature request'lar welcome.

Agar emulator-on-the-critical-path tsiklidan charchagan bo'lsangiz — sinab ko'ring va nima buzilganini ayting. Har bir bug report keyingi versiyani yanada barqaror qiladi.

#JetpackCompose #ComposeMultiplatform #Kotlin #Android #AndroidDev #IntelliJIDEA #AndroidStudio #DevTools #OpenSource #JetBrainsPlugin
