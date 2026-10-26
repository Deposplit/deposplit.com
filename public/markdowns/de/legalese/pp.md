# Datenschutzerklärung

Stand: 1. November 2026

Diese Erklärung gilt für die Website deposplit.com und für die Deposplit-Apps für Android und iOS.
Sie legt dar, welche Personendaten die Squeng AG bearbeitet, wozu und wie lange. Sie richtet sich
nach dem Bundesgesetz über den Datenschutz (DSG) und, für Personen in der EU und im EWR, nach der
Datenschutz-Grundverordnung (DSGVO).

## Kurz gesagt

- Keine Analyse, kein Tracking, keine Werbung und keine Drittdienste, weder auf der Website noch in
  den Apps.
- Geheimnisse werden auf dem Telefon aufgeteilt und verschlüsselt. Das Relais reicht verschlüsselte
  Teile weiter, die es nicht lesen kann.
- Es gibt kein Konto: keinen Namen, keine E-Mail-Adresse, keine Telefonnummer. Das Relais weiss
  nicht, wer hinter einem Schlüssel steht.

## Verantwortliche

Squeng AG, Adresse siehe [Impressum](imprint).
E-Mail: deposplit@squeng.com

## Die Website

### Besuch der Website

Beim Aufruf einer Seite sendet der Browser seine IP-Adresse und die üblichen Angaben einer Anfrage,
etwa den Browsertyp und die bevorzugte Sprache, an den Server. Sie werden gebraucht, um die Seite
auszuliefern, und für nichts anderes verwendet. Deposplit zeichnet sie nicht auf: Es gibt kein
Zugriffsprotokoll, keine Analyse und kein Zählpixel. Scheitert eine Anfrage an einem Serverfehler,
hält das Fehlerprotokoll fest, welche Adresse der Website angefragt wurde, aber nicht, von wem.

Die Server betreibt Clever Cloud SAS, Frankreich. Der Anbieter führt eigene Zugriffsprotokolle,
die zu jeder Anfrage unter anderem die IP-Adresse, den Zeitpunkt und die angefragte Adresse
festhalten. Die Squeng AG kann sie sieben Tage lang einsehen, und der Anbieter bewahrt sie bis zu
einem Jahr auf, um seine gesetzlichen Pflichten in Frankreich zu erfüllen.

Rechtsgrundlage nach DSGVO: Art. 6 Abs. 1 lit. f, das berechtigte Interesse, die Website
auszuliefern und sicher zu betreiben.

### Cookies und lokaler Speicher

Die Website setzt keine Tracking-Cookies. Sie speichert zwei Dinge im Browser, jedes erst auf
Wunsch:

- **`PLAY_LANG`**, ein Cookie mit der im Sprachmenü gewählten Sprache (`en` oder `de`). Es wird
  beim Schliessen des Browsers gelöscht.
- **`bsTheme`**, ein Eintrag im lokalen Speicher des Browsers mit dem am Schalter gewählten
  Farbschema (`light` oder `dark`). Er bleibt, bis er gelöscht wird.

Ohne Sprachmenü und Schalter wird überhaupt nichts gespeichert. Beides lässt sich jederzeit in den
Einstellungen des Browsers löschen. Danach folgt die Website wieder den Sprach- und
Farbeinstellungen des Browsers. Das Speichern einer gewünschten Einstellung bedarf keiner
Einwilligung, darum gibt es auch kein Cookie-Banner.

Schriften, Symbole und Skripte stammen alle von deposplit.com selbst, sodass der Browser mit keiner
anderen Stelle in Kontakt tritt. Die Links zu GitHub und zu squeng.com führen auf andere Websites,
für die deren eigene Datenschutzerklärungen gelten.

## Die Apps

### Auf dem Telefon

Die für andere verwahrten Teile, die Kontakte samt den ihnen gegebenen Namen und die
Bezeichnungen der eigenen Geheimnisse liegen im privaten Speicher der App auf dem Telefon. Das
Geheimnis selbst wird nach dem Aufteilen nicht aufbewahrt. Die Squeng AG hat auf nichts davon
Zugriff. Das Pseudonym auf einer Kontaktkarte geht direkt von Telefon zu Telefon, per QR-Code oder
von Hand eingetippt, und erreicht das Relais nie.

Wie andere App-Daten ist all dies in der Datensicherung des Telefons und in der Übertragung auf ein
neues Telefon enthalten, sofern diese eingeschaltet sind, damit ein Telefonwechsel nichts verliert.
Android verschlüsselt seine Datensicherung mit der Bildschirmsperre des Telefons. Apple verschlüsselt
das iCloud-Backup mit Schlüsseln, über die Apple verfügt, es sei denn, «Erweiterter Datenschutz für
iCloud» ist eingeschaltet. Die privaten Schlüssel verlassen das Telefon nie, auch nicht in einer
Datensicherung.

- **Kamera:** nur zum Scannen des QR-Codes eines Kontakts. Das Bild wird auf dem Gerät ausgewertet
  und weder gespeichert noch versendet.
- **Face ID und Fingerabdruck:** prüft das Betriebssystem. Die App erfährt nur, ob die Prüfung
  gelungen ist.
- **Mitteilungen:** erstellt die App selbst auf dem Gerät. Es wird kein Push-Dienst genutzt, sodass
  Apple und Google von Deposplit kein Mitteilungs-Token erhalten.

Die Apps enthalten keine Bibliotheken für Analyse, Absturzberichte oder Werbung und senden der
Squeng AG nichts, was das Relais nicht braucht, wie im Folgenden beschrieben.

### Was das Relais bearbeitet

Die Apps reichen Teile über ein Relais von Telefon zu Telefon weiter, standardmässig über jenes der
Squeng AG unter api.deposplit.com. Mit jeder Anfrage erhält das Relais:

- die IP-Adresse des Telefons, die für die Antwort nötig ist und die Deposplit nicht protokolliert
  (zum Hosting-Anbieter siehe oben unter *Besuch der Website*)
- den öffentlichen Schlüssel des Telefons und eine Signatur, mit der das Relais ohne Konto prüft,
  dass eine Anfrage von der Inhaberin oder dem Inhaber des passenden privaten Schlüssels stammt
- zu einem Teil: die öffentlichen Schlüssel von Absender und Empfänger, eine Kennung des
  Geheimnisses, seine für den Empfänger verschlüsselte Bezeichnung, seine Art (etwa Text oder Bild),
  wie viele Teile es gibt und wie viele zum Wiederzusammensetzen nötig sind, Zeitstempel, Signaturen
  und bei Hinterlegungen und Rückgaben den verschlüsselten Teil selbst
- signierte Meldungen, mit denen sich die Apps gegenseitig auf dem Laufenden halten, etwa dass ein
  Schlüssel durch einen neuen ersetzt wurde oder dass ein Teil weiterhin verwahrt wird

Der Teil und seine Bezeichnung sind Ende-zu-Ende für den Schlüssel des Empfängers verschlüsselt,
und das Relais kann keines von beiden lesen. Lesen kann es die Art des Geheimnisses und wie viele
Teile es gibt.

Ein öffentlicher Schlüssel ist eine auf dem Telefon erzeugte Zufallszahl. Er enthält keinen Namen,
und die Squeng AG führt kein Verzeichnis darüber, welcher Person ein Schlüssel gehört. Die Kontakte,
mit denen Schlüssel ausgetauscht wurden, kennen sie natürlich.

**Wie lange:** Einträge auf dem Relais existieren nur, um zugestellt zu werden. Ein verschlüsselter
Teil wird vom Relais gelöscht, sobald er abgeholt worden ist, und ein zurückgegebener Teil, sobald
die gesammelten Kopien in der App freigegeben werden. Jeden anderen Eintrag löscht die App, für die
er bestimmt ist, sobald sie darauf reagiert hat. Einen Eintrag, den keine App mehr abholt, etwa weil
ein Telefon verloren ging oder ein Kontakt gelöscht wurde, kann die Squeng AG jederzeit löschen.

Rechtsgrundlage nach DSGVO: Art. 6 Abs. 1 lit. b, weil die Bearbeitung für die Erbringung des
Dienstes nötig ist.

**Eigene Relais:** Ein Kontakt kann über ein anderes Relais als jenes der Squeng AG erreicht werden.
Die Daten für diesen Kontakt gehen dann an die Betreiberin oder den Betreiber dieses Relais, und es
gilt deren Datenschutzerklärung.

### App-Stores und Käufe

Die Apps werden über Apples App Store und Google Play vertrieben, und In-App-Käufe wickeln Apple und
Google nach ihren eigenen Datenschutzbestimmungen ab. Die Squeng AG erfährt nicht, wer was gekauft
hat: Die Freischaltung wird auf dem Gerät vermerkt. Apple und Google stellen der Squeng AG
zusammengefasste Statistiken zur Verfügung, etwa Downloads, Länder und Absturzberichte von Personen,
die diese in den Einstellungen ihres Telefons erlaubt haben. Daraus lässt sich niemand erkennen.

## Bekanntgabe und Übermittlung ins Ausland

Personendaten werden weder verkauft noch für Werbung weitergegeben. Für die Website, die Apps und
das Relais bearbeitet sie einzig der Hosting-Anbieter Clever Cloud SAS im Auftrag der Squeng AG,
mit Speicherort in Frankreich.

Niemand wird profiliert, und über niemanden wird automatisiert entschieden.

## Rechte

DSG und DSGVO gewähren ein Recht auf Auskunft über die eigenen Personendaten sowie auf deren
Berichtigung oder Löschung. Die DSGVO ergänzt die Rechte auf Einschränkung der Bearbeitung, auf
Widerspruch und auf Datenübertragbarkeit. Anfragen gehen an deposplit@squeng.com.

Weil das Relais nicht weiss, wer hinter einem Schlüssel steht, muss eine Anfrage zu Einträgen auf
dem Relais den betreffenden *Verifizierungsschlüssel* nennen, einen der beiden Schlüssel, die die
App zum Teilen eines Kontakts anzeigt.

Beschwerden sind beim Eidgenössischen Datenschutz- und Öffentlichkeitsbeauftragten (EDÖB,
[edoeb.admin.ch](https://www.edoeb.admin.ch)) möglich oder, in der EU und im EWR, bei der
Aufsichtsbehörde des eigenen Landes.

## Änderungen

Diese Erklärung ändert sich, wenn sich ändert, was bearbeitet wird, und gültig ist stets die Fassung
an dieser Stelle. Jede Änderung lässt sich im öffentlichen
[Repository auf GitHub](https://github.com/Deposplit/deposplit.com) nachvollziehen.
