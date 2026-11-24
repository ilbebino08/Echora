# Spike audio — misure sul device

Data: 2026-11-24
Device: Samsung SM-S916B, Android 17 (API 37), KernelSU root, `adb shell` uid 0, Shizuku uid 0.
Metodo: `tools/spike/run.sh` compila `tools/spike/Spike.java` con javac/d8 e lo esegue con
`app_process` come root, fuori da un'app installata.

## Costruttori di AudioRecord presenti (API 37)

```
public  AudioRecord(int,int,int,int,int)
        AudioRecord(long)
public  AudioRecord(AudioAttributes,AudioFormat,int,int)
private AudioRecord(AudioAttributes,AudioFormat,int,int,Context,int,int)
        AudioRecord(AudioAttributes,AudioFormat,int,int,Context,int,int,AudioRecord-IA)
```

Il costruttore usato è quello privato a 7 parametri, scelto per forma. Esiste anche una
variante nuova con un parametro array (`AudioRecord-IA`) che il codice non usa: la selezione
per forma prende il 7 parametri. Il `Context` di sistema (`ActivityThread.systemMain()`)
funziona e dichiara `uid = 0`, `packageName = android`.

## Misure (3 s per combinazione, RMS sui campioni PCM16)

| source | sorgente | rate/ch | readBytes | rms | esito |
|---|---|---|---|---|---|
| 1999 | HOTWORD | 16000/1 | 96000 | 528 | audio presente |
| 1999 | HOTWORD | 48000/2 | 569600 | 3310 | audio presente |
| 4 | VOICE_CALL | 16000/1 | 96000 | 0 | si apre, silenzio senza chiamata |
| 4 | VOICE_CALL | 48000/2 | 572800 | 0 | si apre, silenzio senza chiamata |
| 8 | REMOTE_SUBMIX | 16000/1 | 96000 | 0 | si apre, silenzio senza chiamata |
| 8 | REMOTE_SUBMIX | 48000/2 | 576000 | 0 | si apre, silenzio senza chiamata |
| 1 | MIC | 16000/1 | 96000 | 1625 | audio presente (più forte, mic diretto) |
| 1 | MIC | 48000/2 | 569600 | 1486 | audio presente |

Nessuna combinazione ha lanciato eccezioni.

## Attribuzione vista da `dumpsys audio`

Durante la cattura:

```
rec update riid:... uid:0 session:... src:HOTWORD not silenced pack:
rec update riid:... uid:0 session:... src:VOICE_CALL not silenced pack:
rec update riid:... uid:0 session:... src:MIC not silenced pack:
```

`pack` è vuoto: la registrazione è attribuita a uid 0 senza package. Non viene silenziata.
Non c'è traccia di un'app associata, quindi l'indicatore privacy non ha un'app da mostrare
(verifica visiva sullo schermo: da confermare a mano).

## Decisioni

1. **Sorgente ambientale: HOTWORD (1999), 16 kHz mono.** Disponibile su questo device, audio
   reale (rms 528 a stanza silenziosa), e non passa dal mic condiviso: nessun conflitto con
   le altre app. 16 kHz mono è anche il formato del buffer, quindi nessuna conversione.
2. **Sorgente di chiamata: VOICE_CALL (4) per prima.** Si apre con uid 0 e legge senza errori;
   dà silenzio fuori chiamata, come atteso. Se dentro una chiamata dà audio, è la soluzione con
   una sola `AudioRecord` e la conversazione intera.
3. **Ripiego: REMOTE_SUBMIX (8)** (si apre anche lui, dà l'audio dell'altra persona) e, se serve
   anche la voce locale, MIC (1) in seconda istanza con mix. MIC funziona, ma è il mic
   condiviso: va usato solo durante la chiamata.
4. Se HOTWORD non fosse disponibile su un altro device, MIC (1) è il ripiego funzionante, con
   gli stessi limiti di conflitto.

## Da verificare a mano

- RMS di `src:VOICE_CALL` e `src:REMOTE_SUBMIX` **durante** una telefonata reale.
- RMS di `src:REMOTE_SUBMIX` durante una chiamata WhatsApp.
- Cosa mostra l'indicatore privacy sullo schermo mentre Echora cattura.
- Mic spento dal toggle di sistema: la cattura si interrompe o restituisce zeri.
