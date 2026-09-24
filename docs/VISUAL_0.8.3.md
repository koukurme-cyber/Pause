# Пауза 0.8.3 — перенос утверждённого концепта

База: `visual-redesign-0.8.2`, коммит `9fc012babbca6f709907f05aa4183afa13de97de`.
Отдельная ветка: `visual-concept-assets-0.8.3`. В main изменений нет.

## Изменения

- `ui/PauseRoot.kt`: фоновые ресурсы вместо градиента; активный экран с кругом прогресса и сеткой поверх пейзажа; компактный прежний колёсный выбор; ограниченная доступной высотой сетка экрана проверки.
- `data/PauseStore.kt`: только длительность сессии для отображения прогресса; блокировка и завершение по-прежнему используют прежний `sessionEndEpochMs`.
- `res/drawable-nodpi/pauza_active_concept_bg.webp`, `pauza_ui_bg.webp`: самостоятельные изображения без UI.
- `res/drawable/ic_pauza_leaf.xml`: отдельный ресурс значка листа.
- `app/build.gradle.kts`: версия 0.8.3 (53), зависимости инструментальных тестов.
- `androidTest/.../VisualFlowTest.kt`, `.github/workflows/build-apk.yml`: сборка APK и проверка реальной Activity на отдельном CI-эмуляторе.

Код `domain/*` и `InstalledAppsRepository.kt` совпадает с `consumer-strict-0.7.4`. Набор русских строк в PauseRoot не изменён.

## Графика

Режим: встроенный imagegen; референс: `03_APPROVED_CONCEPT.png` из пользовательского пакета.

Active prompt: Create production background asset for Android app. Use the RIGHTMOST phone screen in reference as exact style/composition reference. Extract/reconstruct ONLY its full-bleed background landscape without ANY user interface: no phone frame, no status bar, no text, no icons, no leaf symbol, no timer, no circle, no controls. Portrait 9:20 aspect. Same muted pale warm ivory cream sky filling top 40%, misty sage green forested mountain sides beginning around 36% height, calm lake near middle/lower half, soft cream mist across lake and bottom, detailed soft conifer trees along lower edges. Match reference's quiet low-contrast illustrated photographic landscape, color and placement very closely; upper area exceptionally calm and light for black timer overlay. Single standalone rectangular background image, no mockup.

UI prompt: Production Android background image only. Reference is LEFTMOST and MIDDLE phone screens: reconstruct that soft ivory cream paper-like background with subtle pale sage green translucent botanical leaves along upper right margin and a soft sage haze at bottom. Portrait 9:20 image, full bleed, no phone frame, no UI, absolutely no text numbers symbols icons or cards. Match approved reference closely, very low contrast, middle and left mostly blank warm off-white, leaves only on upper right edge fading away by 40% height. An actual refined botanical bitmap background for black text and white cards overlays, not a mockup.

## Границы проверки

Скриншоты создаёт UiDevice из работающего Android-приложения, а не генератор изображений. Тесты устанавливают разрешения только на одноразовом CI-эмуляторе. Приложение не получает новых способов обхода настройки разрешений.

Итоги конкретного запуска: `checks.txt`, `build.txt` и HTML-отчёт в архиве verification соответствующего prerelease. `checks.txt` содержит только реально достигнутые успешные проверки, а не список намерений. Ошибка теста не означает, что последующие пункты выполнены.

Физический телефон/HyperOS не проверен. Поведение системных разрешений, Home и Recents на телефоне пользователя не следует из результата на эмуляторе.
