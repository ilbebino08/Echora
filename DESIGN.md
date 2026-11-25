---
version: alpha
name: Echora
description: Interfaccia Android ispirata ai registratori audio portatili.
---

# Echora

Il compito principale è salvare gli ultimi minuti di audio. La home mette la capacità del buffer e lo stato dell’ascolto nello stesso quadrante. Le tacche sono parte del disegno del quadrante. Il valore numerico esprime la capacità configurata in minuti.

## Palette e tipo

Il tema Compose in `app/src/main/java/com/echora/app/ui/EchoraTheme.kt` usa i colori di sistema di Android (Monet) in modalità chiara e scura. Il quadrante usa le superfici inverse della stessa palette. La tipografia Android sans serif serve navigazione, titoli e controlli. Il display monospaziato richiama i numeri di un registratore.

## Schermate

- Home con accesso alle impostazioni, quadrante, durata della registrazione e azioni di salvataggio e ascolto.
- Impostazioni con durata del buffer, due interruttori e un salvataggio esplicito. Indietro chiude la schermata senza applicare la bozza.
- Le schermate condividono contenitori, forme e stili Material 3. Il contenuto scorre sotto le barre di sistema e resta centrato su finestre larghe. La snackbar di Scaffold gestisce il feedback e la condivisione.
- Le etichette sono in italiano. Le azioni restano testuali e le aree tattili rispettano le dimensioni minime di Material 3.
