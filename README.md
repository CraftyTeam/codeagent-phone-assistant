# CodeAgent Phone Assistant

Android assistant local pentru utilizator final. Se instalează ca APK, își pregătește automat motorul local la prima pornire și apoi poate executa acțiuni pe telefon prin Android APIs și serviciul de control al interfeței.

## Experiența utilizatorului

1. Instalează APK-ul.
2. Deschide CodeAgent.
3. La prima pornire, aplicația descarcă automat fișierele necesare și afișează progresul. Descărcarea poate fi reluată dacă este întreruptă.
4. CodeAgent verifică integritatea fișierelor înainte să le folosească.
5. Android cere o singură dată permisiunea necesară pentru controlul interfeței; onboarding-ul explică exact ce trebuie activat.
6. După configurare, utilizatorul intră direct în interfața de chat. Nu există selector de model, import manual sau buton tehnic pentru Accessibility.

## Motor local

Aplicația folosește LiteRT-LM 0.16.0 și descarcă automat o variantă Qwen3 0.6B INT4 optimizată pentru execuție locală pe telefon:

- model: `Qwen3-0.6B`
- format: `.litertlm`
- cuantizare: INT4
- dimensiune: aproximativ 331 MB
- licență: Apache-2.0
- repository model: `litert-community/Qwen3-0.6B-int4`

URL-ul, numele fișierului, dimensiunea și SHA-256 sunt configurabile din `gradle.properties`. Aplicația nu depinde de un model gated și utilizatorul nu are nevoie de cont Hugging Face.

## Ce poate face

- function/tool calling local
- lanternă on/off
- volum media
- deschidere aplicații
- Wi-Fi / Bluetooth / Settings
- Maps
- dialer și SMS composer
- email composer
- creare contact
- creare eveniment calendar
- inspectarea UI-ului curent
- tap pe text vizibil
- scriere în câmpuri editabile
- scroll
- Back / Home / Recents / Notifications
- rutare directă pentru comenzi românești uzuale

## Cerințe

- Android 12+ / API 31+
- recomandat minimum 6 GB RAM
- aproximativ 500 MB spațiu liber la prima configurare

## Build

GitHub Actions construiește automat APK-ul la fiecare modificare de cod pe `main` și publică ultima versiune ca GitHub Release.

APK direct:

https://github.com/CraftyTeam/codeagent-phone-assistant/releases/download/phone-assistant-latest/codeagent-phone-assistant.apk

## Arhitectură

`MainActivity -> ModelBootstrapper -> AssistantEngine -> LiteRT-LM -> PhoneTools -> Android APIs / PhoneAccessibilityService`

Modelul și conversația rulează local. Internetul este necesar la prima pornire pentru descărcarea fișierelor și ulterior doar dacă acțiunea cerută deschide un serviciu online.

## Securitate

Serviciul de control poate vedea și opera interfața altor aplicații după ce utilizatorul îl activează explicit în Android. Acțiunile SMS și apel rămân în composer/dialer și nu sunt trimise sau inițiate silențios.
