# 🎙️ EventMic Pro

**Micrófono profesional para animación de eventos** — App Android con cancelación de ruido, Audio HD y soporte Bluetooth.

---

## ✨ Funcionalidades

| Función | Descripción |
|---|---|
| 🎙️ Micrófono en vivo | Captura y reproduce audio en tiempo real |
| 🔇 Noise Cancelling | Cancelación de ruido con hardware nativo del dispositivo |
| ✨ Audio HD | 48kHz / 16-bit para máxima calidad |
| 🔁 Cancelación de Eco | Elimina retroalimentación acústica |
| 📻 Bluetooth | Soporte para auriculares/headsets Bluetooth |
| 🔊 Control de Volumen | Slider de 0–100% |
| 📈 Control de Ganancia | -∞ a +6dB con compresor soft-knee |
| 📊 VU Meter | Medidor de nivel de audio en tiempo real |
| 🔔 Foreground Service | Funciona con la pantalla apagada |
| 🌙 Modo oscuro | UI profesional dark mode |

---

## 🚀 Compilar con GitHub Actions

### 1. Crear repositorio en GitHub

```bash
git init
git add .
git commit -m "Initial commit - EventMic Pro"
git remote add origin https://github.com/TU_USUARIO/EventMic.git
git push -u origin main
```

### 2. GitHub Actions compila automáticamente

Cada push a `main` activa el workflow que:
- Compila el APK de Debug y Release
- Sube los APKs como **artifacts** descargables
- Crea un **Release** automático con los APKs adjuntos

### 3. Descargar el APK

Ve a: `GitHub → Tu repo → Actions → Último workflow → Artifacts`

O en: `GitHub → Tu repo → Releases → Última versión`

---

## 🛠️ Compilar localmente

### Requisitos
- Android Studio Hedgehog o superior
- JDK 17
- Android SDK API 26+

### Pasos

```bash
# Clonar y compilar
git clone https://github.com/TU_USUARIO/EventMic.git
cd EventMic
./gradlew assembleDebug

# APK generado en:
# app/build/outputs/apk/debug/app-debug.apk
```

---

## 📱 Instalación en dispositivo

1. Transfiere el APK al teléfono
2. En **Ajustes → Seguridad** → activa **"Fuentes desconocidas"**
3. Abre el APK y sigue el asistente de instalación

---

## 🎛️ Cómo usar

1. **Abre la app** → toca el botón grande del micrófono
2. Concede permisos de **micrófono** cuando se soliciten
3. Activa **Cancelación de Ruido** para filtrar el ambiente
4. Activa **Audio HD** para máxima calidad (48kHz)
5. Ajusta **Volumen** y **Ganancia** según el recinto
6. Si usas auricular Bluetooth, activa la opción correspondiente
7. El micrófono **sigue funcionando con pantalla apagada**

---

## 🔧 Arquitectura técnica

```
MainActivity          → UI principal + controles
AudioService          → Foreground service (MediaRecorder.AudioSource.MIC)
├── AudioRecord       → Captura de audio (PCM 16-bit, 48kHz)
├── AudioTrack        → Reproducción en tiempo real
├── NoiseSuppressor   → Cancelación de ruido (hardware)
├── AcousticEchoCanceler → Cancelación de eco (hardware)
├── AutomaticGainControl → Control automático de ganancia
└── DSP Pipeline      → Noise gate + Compressor + Limiter
SettingsActivity      → Preferencias guardadas
```

### Pipeline de procesamiento de audio
```
Micrófono → [Noise Gate] → [Gain] → [Compressor] → [Volume] → [Hard Limiter] → Altavoz
```

---

## 📋 Permisos requeridos

- `RECORD_AUDIO` — Acceso al micrófono
- `MODIFY_AUDIO_SETTINGS` — Configurar altavoz/Bluetooth
- `BLUETOOTH_CONNECT` — Auriculares Bluetooth
- `FOREGROUND_SERVICE_MICROPHONE` — Funcionar en segundo plano
- `WAKE_LOCK` — Evitar que el CPU se duerma durante la transmisión

---

## 📄 Licencia

MIT License — Libre para uso personal y comercial.
