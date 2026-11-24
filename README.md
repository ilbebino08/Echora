# Echora

Dashcam audio per Android: ascolta di continuo dal microfono e tiene in memoria gli ultimi
minuti. Quando succede qualcosa che vale la pena tenere, apri l'app e salvi quel pezzo.

Stato: **in sviluppo**. Non c'è ancora un APK installabile.

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

- Android 12 o superiore.
- Root (KernelSU/Magisk) con Shizuku in esecuzione come root, oppure Shizuku avviato via ADB
  (in quel caso va rilanciato dopo ogni riavvio: senza, l'app resta su "servizio non
  disponibile").
- Non è pubblicabile sul Play Store e non è progettata per esserlo.

## Compilare

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Serve JDK 17+ (in questo repo: `/opt/android-studio/jbr`) e l'Android SDK con la piattaforma 36.

## Documenti

- `docs/superpowers/specs/2026-11-24-echora-design.md` — design
- `docs/superpowers/plans/2026-11-24-echora.md` — piano di implementazione
- `docs/accesso-microfono-privilegiato.md` — analisi del pattern usato da Ambient Music Mod
- `docs/spike-audio.md` — misure delle sorgenti audio sul telefono di riferimento

## Note

Il pattern di cattura è quello di [Ambient Music Mod](https://github.com/KieronQuinn/AmbientMusicMod)
di Kieron Quinn, che è GPL-3.0. Qui il codice è riscritto da zero; il documento di analisi
descrive il comportamento, non riporta file del progetto originale.

Registrare una conversazione a cui partecipi è lecito in Italia; diffonderla no. I file
salvati restano sul telefono: la responsabilità di cosa ci finisce dentro è di chi usa l'app,
sul proprio telefono.
