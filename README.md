# Jarvis (Android)

Anil's personal Telugu voice assistant for Android.

## డౌన్‌లోడ్

ఈ పేజీలో కుడివైపు **Releases** → తాజా వెర్షన్ → `Jarvis.apk` డౌన్‌లోడ్ చేసి ఇన్‌స్టాల్ చేయండి.

## ఏం చేయగలదు

- తెలుగులో వినడం, తెలుగులో మాట్లాడటం (Android వాయిస్ టైపింగ్ + Google Text-to-speech)
- "Hey Jarvis" అని పిలిస్తే మేల్కోవడం ([openWakeWord](https://github.com/dscripka/openWakeWord), ఫోన్‌లోనే, ఏ key అవసరం లేదు)
- కాల్స్ (4 సెకన్ల కౌంట్‌డౌన్, రద్దు చేయొచ్చు), SMS (పంపే ముందు అడుగుతుంది), WhatsApp మెసేజ్
- అలారం, టైమర్, వాతావరణం (Open-Meteo), ఇంటర్నెట్ సెర్చ్ / వార్తలు
- యాప్‌లు తెరవడం, Google Maps నావిగేషన్, YouTube, ఫ్లాష్‌లైట్, బ్యాటరీ స్టేటస్
- జ్ఞాపకాలు, మిషన్లు (టాస్క్‌లు), ఫోటో విశ్లేషణ

## సెటప్

1. యాప్ తెరిచి ⚙️ సెట్టింగ్స్‌లో OpenAI లేదా Anthropic API key పెట్టండి.
2. అడిగిన అనుమతులు (మైక్, కాల్స్, SMS, కాంటాక్ట్స్, లొకేషన్) ఇవ్వండి.
3. వేక్ వర్డ్ కావాలంటే సెట్టింగ్స్‌లో "వేక్ వర్డ్ ఆన్" చేసి, "Display over other apps", బ్యాటరీ సేవర్ మినహాయింపు ఇవ్వండి.

API keys ఫోన్‌లో మాత్రమే ఉంటాయి; ఈ కోడ్‌లో ఏ key లేదు.

## Build

Every push builds a signed APK with GitHub Actions (`.github/workflows/build.yml`) and publishes it as a release.
The signing key in `app/jarvis.keystore` is for this personal sideloaded app only.
The wake word models are downloaded during the build from openWakeWord v0.5.1 (models licensed CC BY-NC-SA 4.0, personal use).
