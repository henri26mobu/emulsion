# Émulsion

Filtres photo et vidéo inspirés du cinéma : Fight Club, Se7en, Matrix, Blade Runner 2049, Mad Max, Grand Budapest, Amélie, Dune, Raging Bull. **100 % local** : l'APK n'a pas la permission réseau, rien ne quitte le téléphone.

Deux versions du même code React + Vite + TypeScript :

| | APK Android (Capacitor) | Web / PWA |
|---|---|---|
| Photos | oui | oui |
| Vidéos | oui (export GPU, son conservé) | non |
| Recevoir via « Partager » de la Galerie | oui | non |
| Enregistrer dans la Galerie (album Emulsion) | oui | téléchargement du JPEG |
| Partager | feuille de partage Android | Web Share API (Chrome) |

L'appli ne prend pas de photos elle-même : tu prends tes photos avec l'appli caméra du téléphone (modes Pro, Nuit, Portrait, 200 MP, vidéo) et tu appliques le filtre ensuite.

## Utilisation (APK)

- **Choisir une photo / une vidéo** sur l'écran d'accueil, ou **Partager → Émulsion** depuis la Galerie.
- Neuf looks de films et **Neutre**, plus les tiens (**+ Enregistrer**, stockés sur l'appareil). Ce sont des interprétations réglées à l'œil, pas les vraies courbes d'étalonnage.
- Sept curseurs de base : exposition, contraste, saturation, teinte verte, grain, vignette, netteté.
- **Couleurs avancées** : température, vert ↔ magenta, teinte et force des ombres, teinte et force des hautes lumières, noirs relevés, bleach bypass, noir et blanc.
- **Maintenir : original** compare avec l'image sans filtre.
- **Enregistrer** : photo en JPEG pleine résolution dans `Pictures/Emulsion`, vidéo en MP4 H.264 dans `Movies/Emulsion`. Les deux apparaissent dans la Galerie.
- **Partager** : enregistre d'abord, puis ouvre la feuille de partage Android.
- Vidéo : l'aperçu tourne en boucle sans son (touche l'image pour mettre en pause) ; l'export garde le son et se fait plus vite que la durée de la vidéo.

## Structure

```
emulsion/
├── index.html · package.json · tsconfig.json · vite.config.ts
├── capacitor.config.ts              # appId dev.rangermobu.emulsion
├── Dockerfile · nginx.conf · docker-compose.yml · vercel.json · netlify.toml   # version web
├── public/                          # manifest.webmanifest, sw.js, icônes PWA
├── src/
│   ├── main.tsx                     # entrée ; service worker (web seulement)
│   ├── App.tsx                      # accueil, sélecteurs, réception des partages
│   ├── Editor.tsx                   # aperçu, curseurs, préréglages, enregistrer, partager
│   ├── filter.ts                    # shader WebGL2 + Renderer + export JPEG
│   ├── media.ts                     # type photo | vidéo
│   ├── native.ts                    # pont JS ↔ plugin Android (APK uniquement)
│   └── presets.ts                   # préréglages dans localStorage
└── android/                         # projet Android généré par Capacitor
    └── app/src/main/java/dev/rangermobu/emulsion/
        ├── MainActivity.java        # enregistre le plugin, transmet les partages reçus
        ├── PhotoSaverPlugin.java    # MediaStore, partage, sélecteur vidéo, export vidéo
        ├── VideoExporter.java       # Media3 Transformer : décodage → filtre → H.264
        └── FilterEffect.java     # le filtre en GLSL ES 1.00 pour la vidéo
```

**Le filtre existe en deux exemplaires** : `src/filter.ts` (photos et aperçu, WebGL2) et `FilterEffect.java` (export vidéo, GLSL ES 1.00). Si tu changes la formule d'un côté, change-la de l'autre, sinon l'aperçu et l'export divergent. Seul le hash du grain diffère volontairement.

## Intégration continue

`.github/workflows/build.yml` s'exécute à chaque push et pull request :
- **apk** : vérification des types, build web, `cap sync`, `gradlew assembleDebug`. L'APK debug est téléchargeable dans les *Artifacts* de l'exécution.
- **docker** : construit l'image de la version web, sans la publier.

## Compiler l'APK

Prérequis : Node 20+, JDK 21, SDK Android avec `platforms;android-35` et `build-tools;35.0.0`, licences acceptées. Variables d'environnement :

```bash
export ANDROID_HOME=/opt/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
```

Puis :

```bash
npm install
npm run build                 # vérifie les types et construit dist/
npx cap sync android          # copie dist/ dans le projet Android
cd android && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

L'APK est dans `android/app/build/outputs/apk/debug/app-debug.apk` (≈ 20 Mo). Sur Xiaomi/HyperOS, l'installation par USB demande d'activer **Installer via USB** dans les options pour les développeurs.

À savoir :
- **Media3 est en 1.6.1** : les versions plus récentes (1.11) exigent le SDK Android 36 et un Gradle plus récent.
- `minSdkVersion` est à **29** (Android 10), nécessaire pour ranger les fichiers dans un album via MediaStore.
- Le manifeste ne déclare ni `INTERNET` ni `CAMERA`.
- C'est un APK **debug** (signé avec la clé de debug). Pour un APK de release signé, il faut une clé de signature à toi.

## Déboguer

```bash
adb logcat --pid=$(adb shell pidof dev.rangermobu.emulsion)
```

Inspecter la page de l'appli (build debug), par exemple pour cliquer des boutons depuis un script :

```bash
PID=$(adb shell pidof dev.rangermobu.emulsion)
adb forward tcp:9333 localabstract:webview_devtools_remote_$PID
# puis chrome://inspect, ou Playwright connectOverCDP('http://localhost:9333')
```

## Version web (PWA)

```bash
npm run dev          # http://IP-du-PC:5173
npm run build && npm run preview
```

Elle n'a ni vidéo ni réception des partages ; l'enregistrement télécharge le JPEG. Le HTTPS reste nécessaire pour **installer** la PWA et activer le service worker (hors `localhost`), mais plus pour la caméra, qui n'est plus utilisée.

### Déployer sur le NAS (Portainer)

Portainer ne construit pas depuis un dossier local : deux options.

**A. Stack depuis un dépôt Git (recommandé).** Pousse le dossier sur GitHub/Gitea, puis Portainer → *Stacks* → *Add stack* → **Repository** → *Compose path* : `docker-compose.yml` → *Deploy*. L'appli répond sur le port `8080`.

**B. Image construite sur le PC.**
```bash
docker build -t emulsion:latest .
docker save emulsion:latest | gzip > emulsion.tar.gz
```
Portainer → *Images* → *Import*, puis une stack avec `image: emulsion:latest` et `ports: ["8080:80"]`.

Le conteneur parle en HTTP. Pour le HTTPS, mets devant lui un reverse proxy (Synology *Proxy inversé*, Nginx Proxy Manager), un Cloudflare Tunnel, ou `tailscale serve --bg --https=443 http://127.0.0.1:8080`.

### Hébergement gratuit

**Vercel** : pousse sur GitHub, importe le dépôt, *Deploy* (ou `npx vercel --prod`). **Netlify** : même principe, `netlify.toml` fournit la commande et le dossier (ou `npm run build && npx netlify deploy --prod --dir=dist`).

## Limites connues

- Une seule photo ou vidéo à la fois (pas de partage multiple).
- Le JPEG exporté n'a pas de métadonnées EXIF (date de prise de vue, lieu) ; la vidéo non plus.
- Le shader suppose du SDR : une vidéo HDR (HDR10+, Dolby Vision) peut avoir des couleurs fausses.
- Une photo ou vidéo reçue est copiée dans le cache de l'appli avant traitement : prévoir de la place pour les grosses vidéos.
- Les photos dépassant la taille maximale de texture du GPU sont réduites à cette taille.
