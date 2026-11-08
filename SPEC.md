# SPEC: TCG Scan — scanner et répertorier ses cartes Pokémon sur Android
date: 2026-11-08   status: final

## Goal
Les collectionneurs de cartes Pokémon n'ont pas d'app de scan à la fois gratuite, ouverte et utilisable hors ligne. TCG Scan est une app Android 100 % gratuite et open source (GPL-3.0, sans freemium, pub, compte ni serveur à opérer) qui reconnaît des cartes physiques FR et JP en rafale, sur le téléphone, et les range dans une collection locale avec wishlist et listes.
Succès : un collectionneur scanne une pile mélangée, valide un écran de revue, et sa collection est juste, avec 95 % des cartes FR reconnues du premier coup en moins de 2 s.

## Context
- Greenfield : repo vide, pas encore initialisé en git. JDK 21, `adb` et SDK Android présents sur la machine (`~/Android/Sdk`, `ANDROID_HOME` non défini).
- Le dossier se nomme `tgc-scan-app`. Le nom retenu est TCG Scan : package `org.tcgscan.app`, repo public à nommer `tcg-scan-app`.
- Stack : Kotlin, Jetpack Compose, CameraX, ONNX Runtime (vision et OCR), Room/SQLite. minSdk 26 (Android 8).
- Distribution : APK sur GitHub Releases uniquement.
- Source de données : API TCGdex (`https://api.tcgdex.net/v2/{lang}/...`), images sur `assets.tcgdex.net`.

Mesures TCGdex du 2026-11-08, hors TCG Pocket :

| Langue | Sets (total API) | Cartes physiques | Avec image |
|---|---|---|---|
| FR | 203 | 20 226 | 17 144 (85 %) |
| EN (après v1) | 221 | 21 432 | 19 669 (92 %) |
| JP | 204 | 13 156 | 3 882 (30 %) |

Faits établis sur l'API, qui contraignent la conception :
- TCG Pocket (série `tcgp`, sets `A1`, `A1a`, `A2`, `A2a`, `A2b`, `A3`, `A3a`, `A3b`, `A4`, `A4a`, `B1`, `B1a`, `B2`, `B2a`, `P-A` à la date de mesure) est un jeu numérique et doit être exclu. Filtrer par série, pas par liste figée.
- FR et EN partagent leurs identifiants (20 133 communs). JP a sa propre numérotation et 14 identifiants entrent en collision avec FR : la clé d'une carte est toujours `(langue, id)`.
- `GET /{lang}/cards` ne renvoie que `id`, `localId`, `name`, `image`. Les prix, raretés et variantes exigent un appel par carte.
- L'API a renvoyé par intermittence un corps non JSON `no available server` : tout client doit gérer cette réponse et réessayer.

## Users & Scenarios
Acteur unique : collectionneur, sur son téléphone, sans compte.

1. **Premier lancement** : mention « projet de fan non affilié », choix des packs (FR, JP), téléchargement, app prête et utilisable hors ligne.
2. **Scan en rafale** : l'utilisateur choisit la langue par défaut (FR ou JP), ouvre la caméra, passe ses cartes l'une après l'autre. Chaque carte stabilisée est détectée automatiquement, sans interruption, avec compteur à l'écran. À la fin, l'écran de revue présente trois groupes : reconnues (pré-cochées), à vérifier, déjà possédées. L'utilisateur résout les cartes à vérifier, valide, et les cartes sont ajoutées.
3. **Catalogue** : recherche par nom ou numéro, ou navigation par extension et langue, avec marque « possédée ». Depuis une carte : marquer possédée, ajouter à la wishlist ou à une liste.
4. **Listes** : wishlist et classeurs personnalisés.
5. **Sauvegarde** : export JSON restaurable et CSV via le sélecteur de fichiers Android, import sur le même appareil ou un autre.
6. **Mise à jour des données** : l'app propose un pack plus récent, l'utilisateur accepte, la collection reste intacte.

## Functional Requirements

### Reconnaissance
FR-1: La caméra détecte automatiquement une carte unique dans le cadre, attend qu'elle soit stable, puis la redresse (correction de perspective) avant reconnaissance, sans action de l'utilisateur.
FR-2: La reconnaissance est hybride et entièrement locale : une empreinte visuelle de la carte est comparée à l'index du pack, et l'OCR du nom, du numéro et du code d'extension confirme ou départage les candidats, dont les réimpressions à illustration identique.
FR-3: Une carte sans référence visuelle (70 % des JP, 15 % des FR) est recherchée par OCR seul, nom plus numéro et code d'extension. Elle part toujours en « à vérifier », candidat pré-sélectionné si la correspondance est exacte et unique.
FR-4: Pour une carte FR sans image TCGdex, la CI peut calculer l'empreinte depuis l'image EN de même identifiant, si le lot de test confirme que ça n'abaisse pas la précision.
FR-5: La langue de la carte est déterminée par OCR (alphabet japonais, marqueurs FR/EN tels que `PV` contre `HP`). Si l'OCR échoue, la langue par défaut de la session s'applique et la carte passe en « à vérifier ».

### Scan en rafale et revue
FR-6: Une rafale n'est jamais interrompue par une demande de confirmation. Compteur de cartes détectées et retour haptique ou visuel à chaque détection. Lampe activable.
FR-7: Une même carte détectée sur des images successives ne compte qu'une fois, jusqu'à ce qu'elle ait quitté le cadre.
FR-8: Toute carte sous le seuil de confiance, ambiguë, ou sans référence visuelle arrive en « à vérifier » avec la photo capturée, les 3 meilleurs candidats et un accès à la recherche manuelle.
FR-9: Aucune carte n'entre dans la collection sans validation dans l'écran de revue. Les cartes reconnues avec confiance sont pré-cochées et validables en une action globale.
FR-10: Une carte détectée comme déjà possédée est signalée « déjà possédée » et ne modifie rien.
FR-11: Une carte détectée comme anglaise part en « à vérifier » avec la carte FR de même identifiant proposée. Si l'utilisateur confirme, elle est enregistrée comme possession FR avec `physical_lang = en`, afin de pouvoir la migrer une fois le pack EN disponible.

### Collection et catalogue
FR-12: La possession est binaire (possédée ou non) pour chaque couple `(langue, id)`. Pas de quantité, variante ni état. Posséder une carte en FR ne vaut pas possession en JP.
FR-13: Vue collection filtrable par langue et par extension, recherche par nom ou numéro, retrait d'une possession.
FR-14: Catalogue : recherche par nom ou numéro, navigation extension par extension pour chaque langue installée, marque « possédée » sur chaque carte, ajout ou retrait manuel de possession.
FR-15: Fiche carte : visuel, nom, numéro, extension, langue, statut possédée, appartenance aux listes.

### Wishlist et listes
FR-16: La wishlist est une liste système non supprimable, à laquelle on ajoute n'importe quelle carte du catalogue.
FR-17: Une carte validée comme possédée est automatiquement retirée de la wishlist.
FR-18: L'utilisateur crée, renomme et supprime des listes personnalisées. Une carte, possédée ou non, peut appartenir à plusieurs listes. Supprimer une liste ne modifie pas les possessions.

### Données et packs
FR-19: Un workflow GitHub Actions planifié (hebdomadaire) et déclenchable à la main construit un pack FR et un pack JP, et un `manifest.json` (version, schéma, taille, SHA-256 par pack), publiés sur GitHub Releases.
FR-20: Un pack contient les métadonnées (ids, noms, numéros, extensions, URL d'images), l'index d'empreintes visuelles et les données de recherche OCR. Il ne contient aucune image TCGdex et exclut la série `tcgp`.
FR-21: La CI réutilise d'une exécution à l'autre les empreintes des cartes inchangées, limite son débit vers TCGdex, et réessaie sur réponse non JSON.
FR-22: Si la CI échoue ou si un pack perd plus de 2 % de cartes par rapport au précédent, rien n'est publié et le dernier pack reste en place.
FR-23: Au lancement et avec du réseau, l'app lit le manifest ; si un pack plus récent existe, elle le propose avec sa taille. Le téléchargement est reprenable, vérifié par SHA-256, installé atomiquement, et ne touche jamais la collection.
FR-24: L'utilisateur peut, depuis les réglages, ajouter ou supprimer un pack de langue.

### Images
FR-25: Les visuels sont chargés à la demande depuis TCGdex et mis en cache localement, avec une taille de cache bornée.
FR-26: Si aucun visuel n'existe, l'app affiche la photo de scan de l'utilisateur, sinon une fiche texte. Les photos restent dans le stockage privé de l'app et sont supprimées pour les cartes rejetées à la revue.

### Sauvegarde
FR-27: Export JSON restaurable (possessions dont `physical_lang`, wishlist, listes, version de schéma) et export CSV des possessions, via le sélecteur de documents Android. Les photos de scan ne sont pas exportées.
FR-28: Import JSON au choix fusion (union) ou remplacement, après confirmation, avec résumé des changements.

### Mentions
FR-29: L'écran « À propos » et le README indiquent : projet de fan non affilié à Nintendo, The Pokémon Company ou Creatures ; données fournies par TCGdex ; licences open source des dépendances et des modèles.

## Non-Functional Requirements
- NFR-1 Licence : GPL-3.0 pour le code. Toutes les dépendances et tous les modèles ML sous licence ouverte compatible.
- NFR-2 FOSS strict : aucun Google Play Services, ML Kit, Firebase ni autre composant propriétaire, y compris transitif.
- NFR-3 Coût : aucun serveur à opérer, aucun service payant. Seuls GitHub Actions et Releases du repo public sont utilisés.
- NFR-4 Hors ligne : une fois les packs installés, scan, revue, collection, catalogue et listes fonctionnent sans réseau. Seuls les visuels non encore en cache et les mises à jour nécessitent internet.
- NFR-5 Vie privée : ni compte, ni télémétrie, ni publicité. Les photos ne quittent jamais l'appareil. Permissions limitées à `CAMERA` et aux permissions réseau.
- NFR-6 Performance : moins de 2 s par carte, de la carte stabilisée au résultat (P90), sur appareil de référence.
- NFR-7 Précision : carte correcte en première position pour 95 % des FR et 80 % des JP avec image.
- NFR-8 Compatibilité : Android 8 et plus (API 26), portrait. APK séparés par ABI, `arm64-v8a` et `armeabi-v7a`.
- NFR-9 Extensibilité : schéma de pack et schéma de base versionnés, avec migrations, pour accueillir le pack EN, les prix et la complétion sans perte de collection.

## Edge Cases & Error Handling
EC-1: Premier lancement sans réseau → écran explicatif avec bouton réessayer. Scan indisponible sans pack.
EC-2: Téléchargement interrompu → reprise au point d'arrêt. SHA-256 invalide → fichier supprimé, nouvel essai proposé, ancien pack conservé.
EC-3: Stockage insuffisant → taille requise affichée avant téléchargement, aucune installation partielle.
EC-4: Permission caméra refusée → explication et lien vers les réglages. Catalogue et collection restent utilisables.
EC-5: Plusieurs cartes dans le cadre → pas de capture, invitation à n'en présenter qu'une.
EC-6: Objet qui n'est pas une carte, autre jeu de cartes, carte TCG Pocket imprimée → aucune détection, ou « à vérifier » sans candidat fiable, sans jamais d'ajout automatique.
EC-7: Reflet de holo ou faible lumière empêchant la lecture → « à vérifier » avec photo, et suggestion d'activer la lampe.
EC-8: Carte sous sleeve → prise en charge en meilleur effort, inclus dans le lot de test.
EC-9: Deux exemplaires identiques dans une rafale → une seule possession (FR-12), le second est signalé « déjà possédée » ou fusionné.
EC-10: Carte anglaise sans équivalent FR → reste en « à vérifier » avec le message « anglais non pris en charge », rejet ou recherche manuelle uniquement.
EC-11: Réimpression à illustration identique dont le numéro est illisible → « à vérifier » avec les candidats, jamais de choix silencieux.
EC-12: Pack de la langue détectée non installé → « à vérifier » avec proposition d'installer le pack.
EC-13: App tuée ou appel entrant en pleine rafale → session et photos persistées, reprise proposée à la réouverture.
EC-14: Sortie de l'écran de revue sans valider → session conservée, rien n'est ajouté.
EC-15: Visuel non chargeable hors ligne ou TCGdex indisponible → photo de scan ou fiche texte, sans erreur bloquante.
EC-16: Identifiant possédé disparu d'un pack mis à jour → possession conservée, affichée « carte inconnue », incluse dans les exports.
EC-17: Pack dont le schéma est plus récent que l'app → pack refusé, ancien pack conservé, invitation à mettre à jour l'app.
EC-18: Import d'un fichier corrompu ou de schéma plus récent → refus, aucun changement. Import de cartes dont le pack est absent → conservées, affichées « carte inconnue » jusqu'à installation du pack.
EC-19: Export interrompu ou dossier inaccessible → message d'erreur, aucun fichier partiel présenté comme valide.
EC-20: Suppression d'un pack alors que des cartes de cette langue sont possédées → avertissement, possessions conservées.

## Non-Goals
- Publication sur Play Store ou F-Droid en v1. La rigueur FOSS garde F-Droid possible plus tard.
- Compte, synchronisation cloud, serveur ou backend.
- Quantités, doublons, variantes (normale, reverse, holo) et état des cartes.
- Cartes gradées en boîtier, autres jeux de cartes, cartes TCG Pocket.
- Échanges, fonctions sociales, iOS.
- Redistribution des images TCGdex dans l'app, les packs ou le repo.

## Acceptance Criteria
AC-1: `./gradlew test assembleRelease` réussit depuis un clone propre et produit des APK `arm64-v8a` et `armeabi-v7a`, avec `minSdk` 26.
AC-2: `./gradlew :app:dependencies --configuration releaseRuntimeClasspath | grep -E 'com\.google\.android\.gms|com\.google\.mlkit|com\.google\.firebase'` ne renvoie rien.
AC-3: Le manifest fusionné de l'APK release ne déclare que `CAMERA`, `INTERNET` et `ACCESS_NETWORK_STATE` comme permissions utilisateur.
AC-4: `LICENSE` contient la GPL-3.0. Le README et l'écran « À propos » portent les mentions de FR-29.
AC-5: Un `workflow_dispatch` publie `manifest.json`, un pack FR et un pack JP dont les SHA-256 correspondent au manifest.
AC-6: Un test sur les packs vérifie qu'aucun set `tcgp` n'est présent, que le nombre de cartes égale celui de TCGdex au moment du build, et qu'aucun fichier image n'est inclus.
AC-7: Avec une réponse `no available server` simulée en CI, ou une perte de plus de 2 % de cartes, aucune release n'est créée.
AC-8: Sur un lot physique de 200 cartes FR mêlant époques, holos et sleeves, sur l'appareil de référence, au moins 190 ont la bonne carte en première position. Résultats consignés dans `docs/benchmark.md`.
AC-9: Sur 100 cartes JP du lot, au moins 80 % de celles ayant une référence visuelle ont la bonne carte en première position.
AC-10: Sur ce même lot, le P90 de la stabilisation au résultat est inférieur à 2 s, et au plus 2 % des cartes sont pré-cochées avec une mauvaise identification.
AC-11: En mode avion, packs installés, une rafale de 20 cartes se termine, la revue se valide, et les cartes apparaissent dans la collection.
AC-12: Une carte anglaise scannée n'est jamais pré-cochée, et après confirmation la base contient la possession FR avec `physical_lang = en`.
AC-13: Scanner une carte déjà possédée la signale « déjà possédée » et laisse la collection inchangée.
AC-14: Forcer l'arrêt de l'app en pleine rafale puis la rouvrir propose de reprendre la session avec ses cartes détectées.
AC-15: Une carte de la wishlist validée comme possédée disparaît de la wishlist et reste dans ses autres listes.
AC-16: Export JSON, désinstallation, réinstallation et import restituent à l'identique possessions, wishlist et listes.
AC-17: L'installation d'un pack plus récent laisse le contenu de la collection identique, vérifié par comparaison de deux exports.
AC-18: Une possession dont l'identifiant a disparu du pack reste visible en « carte inconnue » et présente dans l'export.
AC-19: Tests instrumentés ou manuels documentés pour EC-1 à EC-5.

## Priorities
must: FR-1 à FR-3, FR-5 à FR-29, NFR-1 à NFR-9
should: FR-4
later: pack et reconnaissance EN avec migration des possessions `physical_lang = en` ; complétion par set en pourcentage ; valeur marché Cardmarket en euros, variante normale ou à défaut holo, ajoutée au snapshot CI ; publication F-Droid

## Assumed Defaults
Décisions prises sans question explicite, à corriger si besoin :
- Interface en français, chaînes externalisées pour traduction ultérieure.
- Snapshot CI hebdomadaire, et seuil de blocage à 2 % de cartes perdues.
- Appareil de référence : Android milieu de gamme, classe Snapdragon 6 ou équivalent avec 6 Go de RAM, modèle noté dans `docs/benchmark.md`.
- Mesure du temps en P90, limite de 2 % de cartes pré-cochées à tort.
- Une carte validée possédée quitte la wishlist automatiquement.
- Photos de scan absentes de l'export.

## Open Questions
Aucune.
