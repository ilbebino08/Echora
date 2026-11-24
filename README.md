# Echora

Dashcam audio per Android: ascolta di continuo dal microfono e tiene in memoria gli ultimi
minuti. Quando succede qualcosa che vale la pena tenere, apri l'app e salvi quel pezzo.

Stato: **funzionante**. APK installabile via `./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## Come funziona

- Un processo privilegiato (Shizuku, avviato da root tramite Sui o da ADB) apre l'`AudioRecord`
  sulla sorgente `HOTWORD`, così non litiga con le altre app che usano il microfono e non passa
  dal percorso `RECORD_AUDIO` dell'app.
- L'app legge i campioni via Binder e li scrive in un buffer circolare su file: PCM 16 bit,
  mono, 16 kHz (1,92 MB al minuto).
- Il buffer tiene 10 minuti per default, configurabile da 1 a 60.
- Aprendo l'app scegli quanti degli ultimi minuti salvare: viene scritto un `.wav` in
  `Music/Echora/`, condividibile.
- Durante una telefonata o una chiamata VoIP la sorgente cambia per registrare anche la
  conversazione, quando il telefono lo permette.

Echora **non** dichiara il permesso `RECORD_AUDIO`.

## Requisiti

- Android 12 o superiore (API 31+).
- Root (KernelSU/Magisk) con Shizuku in esecuzione come root (Sui), oppure Shizuku avviato via ADB
  (in quel caso va rilanciato dopo ogni riavvio: senza, l'app resta su "servizio non
  disponibile").
- Non è pubblicabile sul Play Store e non è progettata per esserlo.
- Sviluppata e testata su Samsung SM-S916B (Android 17, API 37, KernelSU).

## Installazione

```bash
# Clona e compila
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Oppure scarica l'APK precompilato (se disponibile) e installalo via `adb install`.

## Primo avvio

1. Apri l'app.
2. Concedi il permesso Shizuku quando richiesto (dialog di sistema Shizuku).
3. Su Android 13+, concedi il permesso "Notifiche" per vedere lo stato in foreground.
4. Attiva "Ascolto attivo" — il servizio foreground parte e inizia la cattura.
5. (Opzionale) Attiva "Avvio al boot" per far ripartire la cattura automaticamente dopo il riavvio.

## UI

- **Schermata principale**: stato (In registrazione / In pausa / Mic disattivato / Servizio non disponibile), minuti nel buffer, selettore durata (1–30 min, limitato ai minuti presenti), pulsante "Salva gli ultimi N minuti", "Pausa/Riprendi", "Impostazioni".
- **Salvataggio**: genera `Echora_YYYYMMDD_HHMMSS_ultimiNm.wav` in `Music/Echora/`, mostra snackbar con azione "Condividi" (`ACTION_SEND`).
- **Impostazioni**: slider minuti buffer (1–60, mostra MB), switch "Ascolto attivo", switch "Avvio al boot".

## Chiamate (telefoniche e VoIP)

Quando rileva una chiamata (polling `AudioManager.mode` + `getActiveRecordingConfigurations()`):

1. Prova `VOICE_CALL` (sorgente 4) — registra entrambe le parti se concessa al processo root.
2. Fallback: `REMOTE_SUBMIX` (8) + `MIC` (1) — mixa downlink e uplink (richiede `CAPTURE_AUDIO_OUTPUT`).
3. Fallback: solo `REMOTE_SUBMIX` — si sente solo l'altra persona.
4. Se tutto fallisce: torna a `HOTWORD` e logga avviso.

Al termine della chiamata, rientra automaticamente su `HOTWORD`. Il buffer **non viene resettato** durante il cambio sorgente.

## Verifica su SM-S916B (Android 17, KernelSU)

| Sorgente | Funziona | Note |
|----------|----------|------|
| `HOTWORD` (1999) | ✅ | RMS ~137–528, non silenzioso |
| `MIC` (1) | ✅ | Non silenzioso |
| `VOICE_CALL` (4) | ⚠️ | Si apre, zero fuori chiamata |
| `REMOTE_SUBMIX` (8) | ⚠️ | Si apre, zero fuori chiamata |

Durante una chiamata reale con speaker attivo: `VOICE_CALL` e `REMOTE_SUBMIX` restituiscono audio.

## Documenti

- `docs/superpowers/specs/2026-11-24-echora-design.md` — design completo
- `docs/superpowers/plans/2026-11-24-echora.md` — piano di implementazione (12 task)
- `docs/accesso-microfono-privilegiato.md` — analisi del pattern usato da Ambient Music Mod
- `docs/spike-audio.md` — misure delle sorgenti audio sul telefono di riferimento
- `.superpowers/sdd/2026-11-24-echora/task-*-report.md` — report per ogni task

## Crediti e licenza

Il pattern di cattura è quello di [Ambient Music Mod](https://github.com/KieronQuinn/AmbientMusicMod)
di Kieron Quinn, che è GPL-3.0. Qui il codice è riscritto da zero; il documento di analisi
descrive il comportamento, non riporta file del progetto originale.

**Licenza**: MIT (codice originale Echora). L'uso di codice GPL-3.0 (AMM) è limitato all'analisi
documentale, non al copia-incolla.

## Note legali

Registrare una conversazione a cui partecipi è lecito in Italia; diffonderla no. I file
salvati restano sul telefono: la responsabilità di cosa ci finisce dentro è di chi usa l'app,
sul proprio telefono.

**Uso personale**: questa app è pensata per il proprio telefono. Non è progettata per essere
installata sul telefono di qualcun altro a sua insaputa.