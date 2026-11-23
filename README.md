# Mein Daten-Digitalisierer

Android-App (und PC-Version im Browser) für den eigenen Papierkram: Rechnungen, Mahnungen,
Gerichtspost und Zahlungen – sortiert nach Aktenzeichen und nach dem Stichtag der Insolvenzeröffnung.

- **Alles verschlüsselt** (AES-256) auf dem Gerät, bevor es gespeichert wird. Der Schlüssel entsteht nur aus deinem Passwort.
- **Texterkennung** aus Fotos und PDFs direkt auf dem Handy (offline).
- **Hinweis-E-Mail** an Alt-Gläubiger, **Widerspruch/Einspruch/Erinnerung** ans Gericht als PDF.
- **Cloud-Sync** über dein eigenes Supabase-Konto → am PC abrufbar, bei Handyverlust nicht weg.
- Bildschirmfotos in der App gesperrt, kein Android-Backup der Daten.

## App installieren
1. Unter **Releases** (rechts auf der Repository-Seite) die neueste `Mein-Daten-Digitalisierer.apk` auf dem Handy herunterladen.
2. Öffnen und „Installieren“ erlauben. Danach aktualisiert sich die App selbst.

## Cloud einrichten (einmalig, ca. 10 Minuten)
1. Auf <https://supabase.com> kostenlos registrieren → **New project** → Region **Frankfurt (eu-central-1)** wählen.
2. **SQL Editor → New query** → Inhalt von [`supabase/einrichtung.sql`](supabase/einrichtung.sql) einfügen → **Run**.
3. **Authentication → Users → Add user → Create new user**: deine E-Mail + ein Konto-Passwort, Haken bei „Auto Confirm User“.
4. **Authentication → Sign In / Providers**: „Allow new users to sign up“ **ausschalten** (dann kann sich niemand sonst anmelden).
5. **Project Settings → API**: *Project URL* und *anon public key* kopieren.
6. In der App „Cloud verbinden“ → URL, Key, E-Mail und Konto-Passwort eintragen.

Danach legst du in der App dein **Tresor-Passwort** fest und bekommst einen **Notfall-Code** – auf Papier aufschreiben!
Das Konto-Passwort schützt den Zugang zur Cloud, das Tresor-Passwort verschlüsselt die Daten. Supabase sieht nur verschlüsselte Daten.

## PC-Version
Einmalig: **Settings → Pages → Source: GitHub Actions**. Danach ist die App unter
`https://robertpahl1987-creator.github.io/Mein-Daten-Digitalisierer/` erreichbar – dort ebenfalls „Cloud verbinden“.
