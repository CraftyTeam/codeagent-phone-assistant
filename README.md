# CTM Local Assistant

Android assistant care rulează LLM-ul local și poate executa acțiuni pe telefon prin Android APIs și AccessibilityService.

## Ce poate face

- Function calling local cu LiteRT-LM
- lanterna on/off
- volum media
- deschidere aplicații
- Wi-Fi / Bluetooth / Settings
- Maps
- dialer și SMS composer
- email composer
- creare contact
- creare eveniment calendar
- inspectarea UI-ului curent prin AccessibilityService
- tap pe text vizibil
- scriere în câmpuri editabile
- scroll
- Back / Home / Recents / Notifications
- rutare directă pentru câteva comenzi românești uzuale, fără inferență LLM

## Cerințe

- Android 12+ (API 31+)
- recomandat minimum 6 GB RAM
- Android Studio recent / AGP 8.13.2
- JDK 17
- Android SDK 36

## Model recomandat

`litert-community/functiongemma-270m-ft-mobile-actions`

Fișierul folosit de Google AI Edge Gallery pentru Mobile Actions este:

`mobile_actions_q8_ekv1024.litertlm`

Modelul este gated de licența Gemma pe Hugging Face. Trebuie să accepți licența și să descarci fișierul în telefon. Modelul nu este inclus în repo sau APK.

Pagina modelului:

https://huggingface.co/litert-community/functiongemma-270m-ft-mobile-actions

## Instalare

1. Deschide proiectul în Android Studio.
2. Lasă Gradle Sync să descarce dependențele.
3. Conectează telefonul Android cu USB debugging sau generează APK-ul debug.
4. Build > Build APK(s).
5. Instalează APK-ul pe telefon.
6. Pornește aplicația și acordă permisiunea Camera pentru lanternă.
7. Apasă `Accessibility` și activează `CTM Local Assistant`.
8. Apasă `Import model` și selectează `mobile_actions_q8_ekv1024.litertlm`.
9. Așteaptă statusul `Model local activ`.

## Build din terminal

Dacă ai Gradle 8.13 instalat:

```bash
gradle :app:assembleDebug
```

APK-ul va fi în:

`app/build/outputs/apk/debug/app-debug.apk`

GitHub Actions construiește automat APK-ul ca artifact la fiecare push pe `main`.

## Exemple

- `aprinde lanterna`
- `deschide YouTube`
- `pune volumul la 30%`
- `deschide setările Wi-Fi`
- `arată Piața Unirii Cluj pe hartă`
- `deschide Chrome și apasă Continue`
- `apasă Accept`
- `scrie test@example.com în câmpul curent`
- `scroll jos`

## Arhitectură

`MainActivity -> DirectCommandRouter -> AssistantEngine -> LiteRT-LM -> PhoneTools -> Android APIs / AccessibilityService`

Inference-ul LLM rămâne local. Acțiunile care deschid servicii online, de exemplu Maps, email sau aplicații web, pot necesita evident internet din partea aplicației țintă.

## Note de securitate

AccessibilityService poate controla UI-ul altor aplicații. Activează-l doar pe telefonul tău și numai dacă accepți acest nivel de acces. Acțiunile SMS și apel sunt implementate cu composer/dialer, nu cu trimitere sau apel silențios.
