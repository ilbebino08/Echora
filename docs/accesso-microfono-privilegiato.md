# Accesso al microfono tramite processo privilegiato (pattern Ambient Music Mod)

Documento di riferimento per replicare in un'altra app Android la logica con cui
[Ambient Music Mod](https://github.com/KieronQuinn/AmbientMusicMod) (AMM) registra audio
ambientale **senza avere il permesso `RECORD_AUDIO` nel proprio manifest**.

Analisi basata sul codice sorgente del repo (commit clonato il 2026-11-24), file citati
con percorso relativo alla root del progetto AMM.

---

## 1. TL;DR

- L'app **non registra**: espone un servizio AIDL che inoltra le chiamate a un
  **user service Shizuku** (o servizio root) in esecuzione con uid `2000` (`com.android.shell`)
  o uid `0`.
- Quel processo privilegiato costruisce un `AudioRecord` **via reflection sui costruttori
  nascosti**, passando un `Context` finto (`ShellContext`) che dichiara
  `AttributionSource(uid=2000, package=com.android.shell)`.
- La sorgente audio non è il microfono normale ma **`AUDIO_SOURCE_HOTWORD` (0x7CF)**, il
  source DSP usato dal hotword/Now Playing di Pixel. Richiede `CAPTURE_AUDIO_HOTWORD`
  (shell su Android 12+) oppure root.
- L'audio PCM viene scritto in una **pipe** (`ParcelFileDescriptor.createPipe()`) e passato
  al servizio di riconoscimento (Now Playing), che fa fingerprinting e match **in locale**.
- L'operazione AppOps `RECORD_AUDIO` viene registrata **per conto dell'app che riceve
  l'audio** (`startProxyOp`), non per l'app helper.
- Tutto il canale AIDL è protetto da un permesso **signature-level**: solo un'app firmata
  con la stessa chiave può chiamarlo. Senza questo, chiunque potrebbe registrare
  tramite il tuo processo shell.

---

## 2. Architettura

```
┌──────────────────────────┐        AIDL (signature permission)        ┌───────────────────────────┐
│  App UI / client         │ ────────────────────────────────────────► │  App "manager" (AMM)      │
│  (Now Playing / tua app) │                                           │  ShellProxyService        │
└────────────▲─────────────┘                                           └─────────────┬─────────────┘
             │ PCM (pipe fd) / byte[]                                             │ Shizuku binder
             │                                                                    ▼
┌────────────┴─────────────┐                                           ┌───────────────────────────┐
│  Servizio riconoscimento │ ◄──── onAudioStreamStarted(readFd) ─────── │  ShizukuService           │
│  (MusicRecognitionService│                                           │  uid 2000 (shell) / 0     │
│   o tuo consumer locale) │                                           │  AudioRecord + ShellContext│
└──────────────────────────┘                                           └───────────────────────────┘
```

Percorsi nel repo AMM:

| File | Ruolo |
|---|---|
| `app/src/main/java/com/kieronquinn/app/ambientmusicmod/service/ShizukuService.kt` | Processo privilegiato: crea/legge/rilascia `AudioRecord`, proxy `MusicRecognitionManager`, sensor privacy |
| `.../service/ShellProxyService.kt` | Servizio esportato nell'app, inoltra l'AIDL al binder Shizuku |
| `.../components/musicrecognition/RootMusicRecognitionManager.kt` | Path "root": bind del servizio di riconoscimento, pipe, streaming PCM, AppOps proxy |
| `.../utils/context/ShellContext.kt` | Finto `Context` con attribution shell/root |
| `.../repositories/ShizukuServiceRepository.kt` | Setup `Shizuku.UserServiceArgs`, bind, permessi |
| `app/src/main/aidl/com/kieronquinn/app/ambientmusicmod/IShellProxy.aidl` | Superficie AIDL (AudioRecord_*, MusicRecognitionManager_*, mic disabled) |
| `app/src/main/AndroidManifest.xml` | Nessun `RECORD_AUDIO`; `ShellProxyService` protetto da `ACCESS_SERVICE` |

---

## 3. Requisiti e vincoli

- Android 9+ (AMM: `minSdk 28`, `targetSdk 35`). Il path pipe/`MusicRecognitionService`
  richiede Android 12+ (`RootMusicRecognitionManager` è `@RequiresApi(S)`).
- **Shizuku** (via ADB, da rieseguire a ogni reboot) **o Sui/Magisk** (root, persistente).
  AMM richiede esplicitamente uno dei due.
- Il processo shell deve avere `android.permission.CAPTURE_AUDIO_HOTWORD`.
  Su Android 12+ è lo standard; su versioni/ROM che non lo concedono serve root
  (fallback dichiarato in `isCompatible()` e nella FAQ del progetto).
- Firma: client e manager devono condividere la signing key (AMM lo ricorda nel README:
  Now Playing va ricompilato con la stessa chiave).
- Distribuzione: questo pattern non è compatibile con le policy del Play Store
  (richiede Shizuku/root e bypassa il modello di permessi runtime).

---

## 4. Come funziona, pezzo per pezzo

### 4.1 Processo privilegiato

```kotlin
// ShizukuServiceRepository.kt
private val userServiceArgs by lazy {
    Shizuku.UserServiceArgs(ComponentName(context, ShizukuService::class.java)).apply {
        daemon(false)
        debuggable(BuildConfig.DEBUG)
        version(BuildConfig.VERSION_CODE)
        processNameSuffix("shizuku")
    }
}
```

`ShizukuService` estende `IShellProxy.Stub` e viene avviato da Shizuku nel processo
`...:shizuku` con l'uid di Shizuku (shell). Se Shizuku gira come root, l'uid è `0`.

Controllo di compatibilità:

```kotlin
private fun doesShellHavePermission(): Boolean =
    context.packageManager.isPermissionGranted(
        "com.android.shell", "android.permission.CAPTURE_AUDIO_HOTWORD"
    )

override fun isCompatible(): Boolean = doesShellHavePermission() || isRoot
override fun isRoot(): Boolean = runWithClearedIdentity { Binder.getCallingUid() == 0 }
```

### 4.2 Attribution: `ShellContext`

`AudioRecord` attribuisce la registrazione al `Context` che lo costruisce. Serve un context
che dichiari di essere shell (o root), altrimenti il controllo permessi fallisce:

```kotlin
class ShellContext(context: Context, private val isRoot: Boolean) : ContextWrapper(context) {
    override fun getOpPackageName(): String =
        "uid:${if (isRoot) 0 else 2000}"

    override fun getAttributionSource(): AttributionSource =
        AttributionSource.Builder(if (isRoot) 0 else 2000)
            .setPackageName(if (isRoot) "android" else "com.android.shell")
            .build()
}
```

Nota: su API < 31 il costruttore di `AudioRecord` non accetta un `Context`; AMM sostituisce
il base context del processo (`replaceBaseContextIfRequired()`), quindi va fatto **prima**
di creare l'oggetto.

### 4.3 Costruzione dell'`AudioRecord`

Il costruttore pubblico richiede `RECORD_AUDIO` per l'uid chiamante. Quello nascosto accetta
un `Context` arbitrario, quindi si usa reflection:

```kotlin
@SuppressLint("MissingPermission", "NewApi")
private fun createAudioRecord(
    attributes: AudioAttributes,
    format: AudioFormat,
    bufferSizeInBytes: Int,
    sessionId: Int,
): AudioRecord {
    val shellContext = ShellContext(context, isRoot)
    val ctor = when {
        Build.VERSION.SDK_INT >= 34 -> AudioRecord::class.java.getDeclaredConstructor(
            AudioAttributes::class.java, AudioFormat::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Context::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
        ) // + halInputFlags
        Build.VERSION.SDK_INT >= 31 -> AudioRecord::class.java.getDeclaredConstructor(
            AudioAttributes::class.java, AudioFormat::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Context::class.java, Int::class.javaPrimitiveType
        ) // + maxSharedAudioHistoryMs
        else -> AudioRecord::class.java.getDeclaredConstructor(
            AudioAttributes::class.java, AudioFormat::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
        )
    }.apply { isAccessible = true }

    val args: Array<Any?> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(attributes, format, bufferSizeInBytes, sessionId, shellContext, 0, 0)
        Build.VERSION.SDK_INT >= 31 -> arrayOf(attributes, format, bufferSizeInBytes, sessionId, shellContext, 0)
        else -> arrayOf(attributes, format, bufferSizeInBytes, sessionId)
    }
    return ctor.newInstance(*args)
}
```

Perché funziona: il costruttore nascosto legge uid/package dall'`AttributionSource` del
context fornito, e il processo in cui girano i controlli è già shell/root. In pratica il
processo privilegiato è esente dalle restrizioni hidden-API (verifica comunque sul target:
il comportamento varia per versione/OEM).

### 4.4 Sorgente HOTWORD

```kotlin
// RootMusicRecognitionManager.kt:291
// "We need to replace the attributes as we can't access the regular mic"
val attributes = AudioAttributes.Builder().apply {
    AudioAttributes.Builder::class.java
        .getMethod("setInternalCapturePreset", Int::class.javaPrimitiveType)
        .invoke(this, 0x7CF) // AUDIO_SOURCE_HOTWORD = 1999
}.build()
```

È il punto chiave: il source `HOTWORD` è quello che il sistema usa per il trigger vocale e
che su Pixel alimenta Now Playing. Non è il mic "normale" (`MIC`/`VOICE_RECOGNITION`), che
resterebbe soggetto ai limiti di background/AppOps dell'app.

Su API 31+ il `Context` passato al costruttore determina l'attribution, ma la *source* viene
presa dagli `AudioAttributes` interni: da qui l'uso di `setInternalCapturePreset`.

### 4.5 Formato e buffer

- PCM 16 bit (`ENCODING_PCM_16BIT`), mono, **16 kHz** (formato usato dalla UI e atteso dal
  modello di Now Playing).
- Buffer = `2 * sampleRate * secondi` (max 24 s in `RootMusicRecognitionManager`;
  la finestra di analisi ambient è ~8 s).
- `captureSession` della richiesta viene passato come `sessionId` dell'`AudioRecord`
  (serve per effetti/AEC lato HAL).
- `ignoreBeginningFrames` permette di scartare i primi frame (AEC che "mangia" l'attacco).

### 4.6 Pipe verso il consumatore (path "root")

```kotlin
val (clientRead, audioSink) = ParcelFileDescriptor.createPipe()
service.onAudioStreamStarted(clientRead, request.audioFormat, serviceCallback)

val record = createAudioRecord(...)
try {
    record.startRecording()
    ParcelFileDescriptor.AutoCloseOutputStream(audioSink).use { out ->
        var total = 0
        val chunk = ByteArray(record.bufferSizeInFrames / seconds) // 2 byte per sample
        while (total < record.bufferSizeInFrames * 2 && binderAlive) {
            val n = record.read(chunk, 0, chunk.size)
            if (n > 0) { total += n; out.write(chunk, 0, n) }
        }
    }
} finally {
    record.release()
}
```

Il servizio di riconoscimento riceve **solo il file descriptor**: il PCM non passa mai per
Binder come `byte[]` in questo path. AMM limita a
`min(request.maxAudioLengthSeconds, 24)`.

**Gotcha:** un servizio Android non può essere bindato "normalmente" dal processo shell.
AMM passa al processo privilegiato l'`IApplicationThread` e l'activity token del client
(`getApplicationThread()` / `getActivityToken()`) e usa
`IActivityManager.bindServiceInstanceCompat(...)` per fare il bind *come se* partisse
dall'app. Nel tuo AIDL prevedi quindi thread e token, altrimenti il bind fallisce o il
servizio viene associato al processo sbagliato.

### 4.7 AppOps attribuito al ricevente

La registrazione viene contabilizzata sull'uid che riceve l'audio (Now Playing), non su AMM:

```kotlin
val status = appOpsManager.startProxyOp(
    AppOpsManager.permissionToOp(Manifest.permission.RECORD_AUDIO)!!,
    serviceInfo.applicationInfo.uid,   // uid del servizio che riceve l'audio
    serviceInfo.packageName,
    attributionTag,
    "MusicRecognitionManager.invokedByUid.$userId"
)
if (status != AppOpsManager.MODE_ALLOWED) throw SecurityException(...)
// ... registra ...
appOpsManager.finishProxyOp(...)
```

Conseguenze pratiche:

- l'indicatore privacy/il log AppOps mostrano l'app ricevente;
- se l'app ricevente non ha `RECORD_AUDIO` (o è bloccata dall'utente), `startProxyOp`
  lancia `SecurityException` → `RECOGNITION_FAILED_AUDIO_UNAVAILABLE`;
- il toggle microfono di sistema (sensor privacy) resta rispettato.

### 4.8 Superficie AIDL

`IShellProxy.aidl` (estratto):

```aidl
void AudioRecord_create(in AudioAttributes attributes, in AudioFormat audioFormat,
                        int sessionId, int bufferSizeInBytes);
void AudioRecord_startRecording();
void AudioRecord_release();
int  AudioRecord_read(out byte[] audioData, int offsetInShorts, int sizeInShorts);
AudioFormat AudioRecord_getFormat();
int  AudioRecord_getBufferSizeInFrames();
int  AudioRecord_getSampleRate();

void MusicRecognitionManager_beginStreamingSearch(in RecognitionRequest request,
                                                  in IRecognitionCallback callback);
boolean isMicrophoneDisabled();
String  addMicrophoneDisabledListener(in IMicrophoneDisabledStateCallback cb);
void    removeMicrophoneDisabledListener(String id);
```

Due modalità d'uso:

1. **Pipe** (ambient): il manager privilegiato apre la pipe e fa streaming lui stesso.
2. **Proxy AudioRecord** (manuale/UI): il client chiama `AudioRecord_create/read` e riceve
   i campioni come `byte[]` via Binder — più semplice, ma copia i dati a ogni read.

### 4.9 Sensor privacy (microfono disattivato)

```kotlin
val sensorPrivacy = Refine.unsafeCast<SensorPrivacyManagerHidden>(
    context.getSystemService("sensor_privacy")
)
sensorPrivacy.addSensorPrivacyListener(Sensors.MICROPHONE) { _, enabled -> ... }
sensorPrivacy.isSensorPrivacyEnabled(Sensors.MICROPHONE)
```

Serve a evitare tentativi di registrazione quando l'utente ha spento il mic a livello sistema
e a mostrare lo stato corretto nella UI. Il controllo *forte* resta comunque nel framework:
con il mic disabilitato `startRecording()` fallisce o restituisce silenzio.

### 4.10 Due percorsi

| Percorso | Quando | Chi registra | Audio |
|---|---|---|---|
| **Root/pipe** | default, ambient e manuale su Android 12+ | `ShizukuService` (shell/root) | `AudioRecord` HOTWORD → pipe → `MusicRecognitionService` |
| **System / on-demand** | `config_defaultMusicRecognitionService` è GSA on-demand | il vero `MusicRecognitionManager` di sistema | gestito da Google/ASI; AMM fa solo da proxy |
| **Proxy AudioRecord** | client che vuole i campioni (UI, test) | `ShizukuService` | `AudioRecord` HOTWORD → `read()` via Binder |

Il path ambient periodico (ogni N minuti, adaptive) è orchestrato dal client; l'helper non
decide quando registrare.

---

## 5. Replicare in un'altra app

### 5.1 Scegli il modello

| Modello | Pro | Contro |
|---|---|---|
| **A. Shizuku/Sui user service** | nessun root obbligatorio su Android 12+; stesso APK | richiede Shizuku attivo; utente deve autorizzare; restart ADB dopo reboot (senza Sui) |
| **B. Servizio root** | sempre disponibile, nessun ADB | root obbligatorio; gestione SELinux/denylist |
| **C. App di sistema / build AOSP** | accesso pieno e stabile | serve firmware custom o privileged app preinstallata |

AMM supporta A e B con lo stesso binder, discriminando con `isRoot()`. Consiglio: parti da A,
aggiungi B come fallback.

### 5.2 Checklist Shizuku

1. Dipendenze: `dev.rikka.shizuku:api`, `dev.rikka.shizuku:provider`,
   `dev.rikka.tools.refine:runtime` (per i tipi nascosti).
2. `ShizukuProvider` nel manifest (`android:permission="android.permission.INTERACT_ACROSS_USERS_FULL"`).
3. Flusso permesso: `Shizuku.checkSelfPermission()` / `requestPermission()`; gestisci
   `Shizuku.isPreV11()` e il binder morto (`binder.addDeathRecipient`).
4. Definisci `IShellProxy.aidl` + `IRecognitionCallback.aidl`, implementa lo `Stub` nel
   servizio privilegiato.
5. `Shizuku.bindUserService(userServiceArgs, connection)`; tieni il binder e riavvia il
   servizio se muore.
6. Nel servizio privilegiato: `isCompatible()` = shell ha `CAPTURE_AUDIO_HOTWORD` **o**
   uid 0.
7. Esponi al client un servizio **non** esportato, oppure esportato con permesso
   `signature` e verifica `Binder.getCallingUid()` contro la tua signing key.

### 5.3 Checklist implementazione

- [ ] `ShellContext` con `getOpPackageName()`/`getAttributionSource()` corretti.
- [ ] Creazione `AudioRecord` con reflection, rami API 30 / 31 / 34.
- [ ] `AudioAttributes` con preset `0x7CF`.
- [ ] Formato: 16 kHz, mono, PCM16; buffer dimensionato sui secondi richiesti.
- [ ] Lock: una sola registrazione alla volta (`synchronized`/`Mutex`); rilascio sempre
      in `finally`.
- [ ] Gestione `getBufferSizeInFrames()` che può lanciare: fallback (AMM usa 128000).
- [ ] Death recipient sul binder del client: interrompi il loop se il client muore.
- [ ] `startProxyOp`/`finishProxyOp` per l'uid ricevente, con attribution tag.
- [ ] Listener `SensorPrivacyManager` per mic off.
- [ ] Foreground service lato client per la registrazione periodica + `SCHEDULE_EXACT_ALARM`
      o WorkManager; rispetta battery saver/bedtime se vuoi lo stesso comportamento.
- [ ] Permesso signature sull'AIDL esportato.

### 5.4 Cosa sostituire se non hai un motore di riconoscimento

Nel pattern AMM la pipe finisce in `MusicRecognitionService` di Google. Nel tuo caso puoi:

- passare la pipe a un tuo `Service`/`processo` che fa buffering e inferenza locale
  (TFLite/ONNX/whisper.cpp…);
- oppure usare il proxy `AudioRecord_read` e consumare i `byte[]` direttamente nel client
  (più semplice, adatto a finestre brevi e UI live);
- salvare su file solo se l'utente lo chiede esplicitamente (AMM espone il salvataggio
  manuale del campione PCM dalla schermata di test).

---

## 6. Insidie note

| Problema | Dettaglio / mitigazione |
|---|---|
| `CAPTURE_AUDIO_HOTWORD` assente su shell | Android < 12 o ROM custom: senza root non funziona. Verifica con `isPermissionGranted("com.android.shell", ...)` |
| Reflection e hidden API | i costruttori cambiano ad ogni release (API 34 aggiunge `halInputFlags`); tieni fallback al costruttore pubblico e degrada con un errore chiaro |
| Source `HOTWORD` su OEM | alcuni vendor la disabilitano o la riservano all'assistente; testa su device reali, non solo emulatore |
| Mic in uso | chiamate/altre registrazioni: il source può restituire silenzio o fallire; replica i controlli "skip" (chiamata, musica in riproduzione, altra registrazione) e `AudioManager.isMusicActive` |
| Indicatore privacy | può comportarsi diversamente quando il recorder è shell/root; verifica cosa mostra il target e documentalo |
| AppOps | se attribuisci al client sbagliato, l'utente vede l'indicatore sull'app sbagliata o la chiamata fallisce; usa `startProxyOp` con uid/package/tag reali |
| Permesso signature | un proxy AIDL esportato senza protezione = qualsiasi app può registrare dal tuo processo shell. Imperativo |
| Batteria | registrazioni periodiche costano; AMM usa adaptive interval, skip in battery saver/bedtime e foreground service |
| Doze/background start | bind e registrazioni vanno avviati da foreground service o job con vincoli; il processo shell non è soggetto agli stessi limiti dell'app |
| Licenza | AMM è **GPL-3.0**: copiare codice impone la GPL al tuo progetto. Gli snippet qui sono riscritture descrittive; verifica la licenza prima di riusare file originali |
| Legale | registrare conversazioni ambientali ha implicazioni normative variabili per paese (consenso): limita la conservazione, non salvare audio di default, informa l'utente |

---

## 7. Verifica (matrice minima)

1. **Dispositivo senza root, Android 12+**, Shizuku attivo: `isCompatible()` true,
   registrazione 8 s, PCM non silenzioso, AppOps attribuito al client.
2. **Stesso device, Shizuku non attivo**: errore pulito, nessun crash, UI che invita a
   configurare Shizuku.
3. **Mic disattivato da toggle di sistema**: listener scatta, nessuna registrazione,
   messaggio dedicato.
4. **Chiamata in corso**: skip/errore, nessun audio di chiamata nel buffer.
5. **Android 13/14/15**: costruttore corretto, `halInputFlags`, nessuna eccezione reflection.
6. **Root al posto di Shizuku**: stesso flusso, `isRoot()` true, attribution `android`/uid 0.
7. **Client non firmato**: chiamata AIDL rifiutata con `SecurityException`.
8. **Processo client ucciso durante la registrazione**: loop termina, `AudioRecord` rilasciato,
   pipe chiusa.

---

## 8. Riferimenti

- Repo: <https://github.com/KieronQuinn/AmbientMusicMod> (GPL-3.0)
- Now Playing (client/APK separato, stessa firma):
  <https://github.com/KieronQuinn/NowPlaying>
- Shizuku: <https://github.com/RikkaApps/Shizuku-API>
- Sui (root): <https://github.com/RikkaApps/Sui>
- API `AudioRecord`, `AudioAttributes`, `MusicRecognitionManager`, `SensorPrivacyManager`
  (Android SDK / AOSP)
