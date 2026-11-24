# Spike audio — misure sul device

Data: 2026-11-24
Device: Samsung SM-S916B, Android 17 (API 37), KernelSU root, `adb shell` uid 0, Shizuku uid 0.
Metodo: `tools/spike/run.sh` compila `tools/spike/Spike.java` con javac/d8 e lo esegue con
`app_process` come root, fuori da un'app installata. Ogni combinazione legge 3 secondi in
blocchi da 3200 byte e scrive il PCM in `/data/local/tmp/spike-<source>-<rate>.pcm`.

## Costruttori di AudioRecord presenti (output grezzo)

```
ctor: public android.media.AudioRecord(int,int,int,int,int) throws java.lang.IllegalArgumentException
ctor: android.media.AudioRecord(long)
ctor: public android.media.AudioRecord(android.media.AudioAttributes,android.media.AudioFormat,int,int) throws java.lang.IllegalArgumentException
ctor: private android.media.AudioRecord(android.media.AudioAttributes,android.media.AudioFormat,int,int,android.content.Context,int,int) throws java.lang.IllegalArgumentException
ctor: android.media.AudioRecord(android.media.AudioAttributes,android.media.AudioFormat,int,int,android.content.Context,int,int,android.media.AudioRecord-IA)
```

Il costruttore usato è il privato a 7 parametri `(AudioAttributes, AudioFormat, int, int,
Context, int, int)`, scelto per forma (prefisso fisso + coda di soli `int`), non per versione.
La variante con `AudioRecord-IA` (array) non viene usata.
Attribuzione del `Context` di sistema:

```
context=android.app.ContextImpl@... package=android attribution=AttributionSource { uid = 0, packageName = android, attributionTag = null, token = android.os.Binder@..., deviceId = 0, next = null }
```

## Misure (esecuzione delle 19:13, 3 s per combinazione)

| source | sorgente | rate/ch | readBytes | rms |
|---|---|---|---|---|
| 1999 | HOTWORD | 16000/1 | 96000 | 137 |
| 1999 | HOTWORD | 48000/2 | 572800 | 3297 |
| 4 | VOICE_CALL | 16000/1 | 96000 | 0 |
| 4 | VOICE_CALL | 48000/2 | 572800 | 0 |
| 8 | REMOTE_SUBMIX | 16000/1 | 96000 | 0 |
| 8 | REMOTE_SUBMIX | 48000/2 | 576000 | 0 |
| 1 | MIC | 16000/1 | 96000 | 121 |
| 1 | MIC | 48000/2 | 569600 | 113 |

I `readBytes` coincidono con la dimensione dei file lasciati sul device in quella stessa
esecuzione (`ls -l /data/local/tmp/spike-*.pcm`). Nessuna combinazione ha lanciato eccezioni.
Una nuova esecuzione può differire di un blocco da 3200 byte (il ciclo legge finché è passato
il tempo, non un numero fisso di blocchi): è il motivo per cui la tabella dichiara l'ora.

L'RMS non è un livello di riferimento: fra due esecuzioni nella stessa stanza HOTWORD 16k è
passato da 528 a 137, e il 48k stereo vale ~5× il 16k mono perché somma due canali. Conta solo
la differenza fra "zero" e "non zero".

## Attribuzione vista da `dumpsys audio`

Sequenza completa di un ciclo (esecuzione delle 19:12; identica a ogni esecuzione):

```
11-24 19:12:42:205 rec update riid:1170679 uid:0 session:1172225 src:HOTWORD not silenced pack:
11-24 19:12:45:319 rec update riid:1170687 uid:0 session:1172233 src:HOTWORD not silenced pack:
11-24 19:12:48:491 rec update riid:1170695 uid:0 session:1172241 src:VOICE_CALL not silenced pack:
11-24 19:12:51:555 rec stop riid:1170695 uid:0 session:1172241 src:VOICE_CALL not silenced pack:
11-24 19:12:51:608 rec update riid:1170703 uid:0 session:1172249 src:VOICE_CALL not silenced pack:
11-24 19:12:54:643 rec stop riid:1170703 uid:0 session:1172249 src:VOICE_CALL not silenced pack:
11-24 19:12:54:814 rec update riid:1170711 uid:0 session:1172257 src:REMOTE_SUBMIX not silenced pack:
11-24 19:12:58:129 rec update riid:1170719 uid:0 session:1172273 src:REMOTE_SUBMIX not silenced pack:
11-24 19:13:01:289 rec start riid:1170727 uid:0 session:1172281 src:MIC not silenced pack:
11-24 19:13:04:351 rec stop riid:1170727 uid:0 session:1172281 src:MIC not silenced pack:
11-24 19:13:04:391 rec update riid:1170735 uid:0 session:1172297 src:MIC not silenced pack:
11-24 19:13:07:435 rec stop riid:1170735 uid:0 session:1172297 src:MIC not silenced pack:
```

Letto: `uid:0`, `pack` vuoto, `not silenced`. La registrazione è attribuita a root senza
package. **Ipotesi da confermare a occhio**: l'indicatore privacy non ha un'app da mostrare e
resta spento. Il formato `pack:` vuoto è quello che il device stampa davvero, non un taglio.

## Decisioni

1. **Sorgente ambientale: HOTWORD (1999), 16 kHz mono.** Misurato: si apre, legge, audio non
   silenzioso. 16 kHz mono è anche il formato del buffer, quindi nessuna conversione. Il
   "non litiga con le altre app" è l'intento del pattern AMM, non una misura di questo spike:
   la prova di concorrenza va fatta con un'altra app che registra (verifica finale).
2. **Sorgente di chiamata: VOICE_CALL (4) per prima.** Misurato fuori chiamata: si apre e legge
   senza errori, con PCM a zero. Che dentro una chiamata dia la conversazione intera è
   l'ipotesi da verificare a mano.
3. **Ripiego: REMOTE_SUBMIX (8)** (si apre e legge, PCM a zero fuori chiamata) e, se serve anche
   la voce locale, MIC (1) in seconda istanza con mix. Che REMOTE_SUBMIX dia la voce
   dell'altra persona è anch'esso da verificare a mano.
4. Se HOTWORD mancasse su un altro device, MIC (1) è il ripiego funzionante, con il limite del
   mic condiviso.

## Dettagli da riusare nell'app

- Sorgente: `AudioAttributes.Builder.setInternalCapturePreset(source)` via reflection, poi il
  costruttore di `AudioRecord` per forma.
- Argomenti del costruttore a 7 parametri: `(attributes, format, bufferSizeInBytes, 0,
  context, 0, 0)`; `sessionId = 0` è `AUDIO_SESSION_ID_GENERATE`.
- `bufferSizeInBytes = max(getMinBufferSize(rate, mask, PCM16), 4 * 3200)`.
- `Context`: un `ContextWrapper` che dichiara l'attribuzione root/shell (nell'app, non
  `ActivityThread.systemMain()`).

## Da verificare a mano

- RMS di `src:VOICE_CALL` e `src:REMOTE_SUBMIX` **durante** una telefonata reale.
- RMS di `src:REMOTE_SUBMIX` durante una chiamata WhatsApp.
- Cosa mostra l'indicatore privacy sullo schermo mentre Echora cattura.
- Mic spento dal toggle di sistema: la cattura si interrompe o restituisce zeri.
- Cattura HOTWORD con un'altra app che registra dal microfono: nessun conflitto.
