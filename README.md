# Émulsion

Filtres photo et vidéo inspirés du cinéma (Fight Club, Se7en, Matrix, Blade Runner 2049, Mad Max, Grand Budapest, Amélie, Dune, Raging Bull, Sin City, John Wick 3). **100 % local** : aucune permission réseau.

Application Android native en Kotlin + OpenGL ES 3, sans dépendance (ni AndroidX, ni Compose).

- 22 presets (films célèbres, looks classiques), LUT `.cube`, courbes RVB, halation, grain réglable, bandes cinémascope, perforations, poussière
- Avant/après glissant, lot de photos, presets partageables (JSON)
- Photos : export JPEG pleine résolution par bandes (mémoire basse)
- Vidéos : export GPU, son conservé
- Réception via « Partager » de la Galerie, enregistrement dans l'album Emulsion

Build : `./gradlew assembleRelease` (minSdk 29).

## À tester sur téléphone

Aucune de ces fonctions n'a été essayée sur un appareil (seule la compilation et la syntaxe des shaders sont vérifiées) :

- [ ] Les shaders se compilent sur le GPU du téléphone (photo et vidéo) ; sinon l'aperçu reste noir et un message d'erreur s'affiche
- [ ] Les 22 nouveaux presets : rendu à ajuster à l'œil (valeurs réglées de mémoire)
- [ ] Import d'une LUT `.cube` (3D) et curseur de force, en aperçu et à l'export
- [ ] Courbes L / R / V / B : déplacement des points, effet identique à l'export
- [ ] Halation : pas de bord visible entre les bandes de 256 lignes à l'export pleine résolution
- [ ] Avant | Après : glisser le trait, y compris sur une vidéo en lecture
- [ ] Lot de photos : plusieurs photos de tailles et d'orientations différentes
- [ ] Bandes cinémascope et perforations sur une photo portrait
- [ ] Partage d'un preset (appui long) et import par le presse-papiers ou un texte partagé
- [ ] Export vidéo avec les nouveaux effets (grain, poussière, halation, LUT)
- [ ] Mise à jour : la clé de signature change à chaque build CI, donc désinstaller avant de réinstaller
