# Echora — design

Data: 2026-11-24
Stato: in attesa di approvazione

## Cos'è e perché

Una dashcam audio per Android: un servizio privilegiato ascolta di continuo dal microfono
e tiene in un buffer circolare gli ultimi N minuti (default 10). Quando succede qualcosa
che vale la pena tenere, apri l'app e salvi gli ultimi N minuti in un file.

Il root (via Sui/Shizuku) c'è perché la cattura non deve entrare in conflitto con le altre
app che usano il microfono, deve sopravvivere a schermo spento, chiamate e ottimizzazioni
batteria, e non deve passare dal percorso normale `RECORD_AUDIO` dell'app.

## Obiettivi

- Cattura continua quando è abilitata: schermo spento, batteria ottimizzata, riavvio.
- Buffer circolare, default 10 minuti, durata configurabile.
- Salvataggio su richiesta degli ultimi N minuti, con N qualsiasi fino alla capienza del
  buffer, in un file `.wav` che si apre con qualunque player.
- Stato sempre leggibile: in cattura / mic disattivato dal sistema / servizio privilegiato
  non disponibile / in pausa per chiamata.
- Nessun permesso `RECORD_AUDIO` nel manifest di Echora.

## Non-obiettivi

- Niente trascrizione, niente AI, niente cloud, niente streaming.
- Niente supporto ufficiale al percorso senza root (Shizuku via ADB): funziona se c'è,
  ma non è la configurazione supportata.
- Niente Play Store.
- Niente registrazioni multiple o selezione di più microfoni.
- Niente conservazione del buffer tra riavvii: dopo un riavvio il buffer riparte vuoto.
- Niente furtività: l'app non cerca di nascondersi al proprietario del telefono e non è
  pensata per essere installata sul telefono di qualcun altro.

## Anti-obiettivi (fallimento anche se "funziona")

- Buffer che contiene silenzio mentre la UI dice "in registrazione".
- File salvati illeggibili o tagliati male sul punto di avvolgimento del buffer.
- Servizio foreground ucciso dal sistema ogni poche ore.
- Batteria inutilizzabile: se il consumo rende il telefono scomodo, il design è sbagliato.

## Vincoli

- Android 12+ (`minSdk 31`): il costruttore nascosto di `AudioRecord` che accetta un
  `Context` esiste da API 31, e `CAPTURE_AUDIO_HOTWORD` per la shell esiste da 12.
- Formato imposto dalla sorgente: PCM 16 bit, mono, 16 kHz → 32 KB/s → 1,92 MB/min.
- Una sola `AudioRecord` alla volta nel processo privilegiato.
- Reflection sui costruttori nascosti con rami per API 31 / 34+.
- AMM è GPL-3.0: il codice va scritto da zero seguendo il documento di analisi
  (`docs/accesso-microfono-privilegiato.md`), non copiato. Credito ad AMM nel README.
- Verifica su device reale prima di costruire la UI: `HOTWORD` può essere assente o
  riservata sull'OEM.

## Approcci considerati

1. **ALSA diretto via root** (`/dev/snd/pcm*`, `tinymix`). Scartato: specifico per modello,
   da riscoprire su ogni telefono, e il documento AMM mostra una via più pulita.
2. **Shizuku/Sui user service + `AudioRecord` con sorgente `HOTWORD` via reflection**
   (scelto). Stesso binder per root (Sui, uid 0) e Shizuku via ADB (uid 2000). Il processo
   privilegiato è esente dalle restrizioni hidden-API, la sorgente HOTWORD non litiga con
   le registrazioni normali delle altre app.
3. **App di sistema / build AOSP.** Scartato: serve firmware custom, molto più lavoro per
   lo stesso risultato.

## Decisioni prese

### Privilegio
- Shizuku API + `ShizukuProvider`; utente root tramite Sui (uid 0) o Shizuku avviato da
  root. `isCompatible()` = `isRoot()` oppure shell con `CAPTURE_AUDIO_HOTWORD`.
- Il servizio privilegiato è un `Shizuku.UserService` (`IShellProxy.Stub` di Echora,
  `processNameSuffix("shizuku")`).
- Binder esposto all'app con permesso `signature` e controllo di `Binder.getCallingUid()`.

### Trasporto audio
- **Proxy `AudioRecord_read` via Binder**, non la pipe di AMM: Echora non ha un servizio di
  riconoscimento a cui passare un fd. Blocchi da 1 s (32 KB) letti in un loop bloccante,
  quindi il ritmo lo dà la `read()` stessa, senza timer.
- Buffer dell'`AudioRecord` lato processo privilegiato: ~4 s.

### Chi possiede il buffer
- Il processo privilegiato possiede solo l'`AudioRecord`: non può scrivere in `filesDir`
  dell'app (uid 2000/0), quindi non tocca nessun file.
- `CaptureService` (processo app) fa il loop di `read()` da 1 s e scrive il buffer. Il
  processo app deve restare vivo perché la cattura continui; se viene ucciso la cattura
  si ferma e il buffer riparte da capo al riavvio. Il foreground service è ciò che lo
  tiene vivo.
- `CaptureService` tiene un `PARTIAL_WAKE_LOCK` finché cattura: la `read()` bloccante ha
  bisogno della CPU anche a schermo spento. Rilasciato in pausa e in teardown.

### AppOps
- Niente `startProxyOp`: Echora non è l'app che riceve uno stream da un servizio di
  riconoscimento, legge i campioni da sé. Con uid 0 (Sui/root) il controllo AppOps non si
  applica; con uid 2000 la cattura è coperta da `CAPTURE_AUDIO_HOTWORD` della shell.
- A chi viene attribuito l'indicatore privacy in questo assetto va misurato nello spike,
  non assunto: se l'indicatore lampeggia a nome di Echora va documentato in UI.

### Buffer
- Un file preallocato in `filesDir/buffer.pcm`, dimensione = minuti · 60 · 32000 byte.
  Scrittura posizionale con avvolgimento (`writeOffset`, `filled`); nessun encoder,
  nessun muxer, taglio esatto al campione.
- Al salvataggio: lettura all'indietro di N minuti da `writeOffset`, gestendo il caso di
  avvolgimento in due segmenti, e scrittura di un WAV con header di 44 byte.
- Reset a ogni avvio del servizio (`filled = 0`), coerente col non-obiettivo "niente
  persistenza tra riavvii".
- Durata massima del buffer 60 minuti (115 MB), controllata anche lo spazio libero.

### Ciclo di vita
- Interruttore principale "Ascolto attivo" in UI e in notifica: spento = `CaptureService`
  fermo, buffer svuotato. Lo stato è persistito; al boot il servizio riparte solo se
  l'interruttore era acceso.
- `CaptureService` foreground con tipo `specialUse` (Echora non dichiara `RECORD_AUDIO`,
  quindi non può usare il tipo `microphone`).
- Avvio al boot via receiver; notifica con stato e azione "Pausa".
- Riavvio automatico dopo il boot **solo con root/Sui**. Con Shizuku via ADB il servizio
  privilegiato non c'è finché non si rilancia ADB: la UI mostra "servizio privilegiato non
  disponibile" e il receiver non fa nulla.
- Watchdog: binder morto, `read()` negativa o `startRecording()` fallita → teardown,
  stato in UI, retry con backoff.
- Chiamata in corso (`AudioManager.mode != MODE_NORMAL`): cattura sospesa, per non
  registrare l'audio della telefonata. Le chiamate VoIP non cambiano `mode`: non le
  rileviamo, la sospensione in quel caso si affida al fatto che la sorgente `HOTWORD` è
  occupata dalla telefonata. Da verificare nello spike; se il buffer contiene audio di
  chiamata, serve un rilevamento in più.
- Mic spento dal toggle di sistema (`SensorPrivacyManager`): cattura sospesa, ripresa
  automatica quando torna disponibile.

### App
- Kotlin, Jetpack Compose, singolo modulo `app` (il codice privilegiato gira nel classpath
  dell'app, come in AMM).
- Package `com.echora.app`.
- Una schermata principale: stato, selettore durata (fino ai minuti effettivamente nel
  buffer), pulsante Salva, ultimi file salvati.
- Impostazioni: minuti di buffer (1–60, default 10), avvio al boot.
- Salvataggio in `MediaStore` (`Music/Echora/Echora_<data>_ultimi<N>m.wav`) + azione
  "Condividi".
- Lingua UI: italiano.

## Cosa resta al builder

- Struttura Gradle, versioni AGP/Kotlin/Compose, nome dei file e dei moduli interni.
- Dettagli dell'AIDL oltre al minimo (`create/start/read/release/getSampleRate/
  isMicrophoneDisabled/mic listener`), compreso l'handshake di versione.
- Come si presenta la lista dei file salvati (basta nome e data).
- Scelta della licenza del progetto, se non si copia codice AMM.

## Verifica

Prima di costruire la UI, spike sul telefono reale:

1. Bind del user service come root (Sui) riesce e `isCompatible()` è true.
2. 30 s di cattura `HOTWORD`: PCM non silenzioso, livello plausibile.
3. Un'altra app registra dal microfono mentre Echora cattura: il flusso di Echora continua.
4. Cosa mostra l'indicatore privacy durante la cattura, e a chi è attribuito (da
   documentare, non da aggirare).
5. Mic spento dal sistema → `isMicrophoneDisabled()` true e cattura sospesa.
6. Chiamata in arrivo → sospensione e ripresa.
7. Chiamata VoIP (WhatsApp/Signal) in corso: cosa finisce nel buffer. Se è audio di
   chiamata, lo spike deve dire quale rilevamento serve.
8. `HOTWORD` non disponibile → fallback a `MIC` con avviso in UI (conflitti possibili).
9. Riavvio con Sui → servizio riparte da solo, buffer vuoto. Riavvio con Shizuku via ADB →
   stato "non disponibile", nessun crash.

Test unitari: avvolgimento del buffer, estrazione WAV a cavallo dell'avvolgimento, durata
limitata ai minuti realmente presenti.
