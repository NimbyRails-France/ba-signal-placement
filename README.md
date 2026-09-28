# Signal Placement

Mod outil Kotlin/Native pour Windows, indépendant des mods de signalisation.
Un signal compatible propose **Répéter ce signal** lorsque Signal Placement est
chargé. Le mod fournisseur de signaux fonctionne normalement sans cet outil.
Toute l'utilisation se fait dans le jeu : il n'y a plus d'application séparée.

## État

Le mod est distribué sur le canal **alpha** du Hub et sur le
[serveur de releases NRF](https://releases.nimbyrails-france.fr/releases/signal-placement/v0.1.0-alpha.1/).
Il nécessite le SDK **0.8.0-alpha.3**. Les tests logiciels couvrent le calcul,
les actions du panneau et le cycle de vie de la DLL ; la recette complète
en partie Windows reste distincte.

## Parcours

1. Sélectionner un signal dont le type propose le service `repeat.v1`.
2. Saisir l'espacement en mètres (de 3 à 100 000 ; valeur initiale 1 000 m).
3. Calculer l'aperçu : signaux temporaires sur la carte, nombre proposé,
   emplacements occupés ignorés et motif d'arrêt. Une modification de l'espacement
   recalcule l'aperçu actif. **Masquer l'aperçu** retire les dessins temporaires.
4. Confirmer la pose. Le mod prépare un ticket et recalcule depuis une nouvelle
   capture ; si le résultat diffère, une nouvelle confirmation est nécessaire.
5. Annuler uniquement la dernière série lorsque le pont le permet.
6. **Fermer** replie le menu et masque l'aperçu. Le bouton **Répéter** permet
   de le rouvrir avec le même espacement. Une opération en cours reste suivie
   avec son ticket ; fermer le menu n'annule ni ne recommence la construction.

Le calcul suit les courbes et les raccords réciproques, sans choisir de branche.
Il s'arrête à une aiguille observée, une connexion inconnue ou une limite de
parcours. L'exhaustivité des jonctions natives reste à qualifier. Une série est
limitée à 64 emplacements ; les fractions aux extrémités sont refusées.
L'aperçu reprend le modèle et le sens de la source via le rendu temporaire natif.
Il ne crée aucun objet persistant. Le cadrage et les couches visibles s'appliquent.
Il disparaît avant la pose, à l'arrêt du mod et au changement de partie ; il est
masqué lorsque le signal source n'est plus édité. Le rendu visuel reste à valider
dans une partie Windows, notamment sur les courbes et les voies superposées.

Une réponse Pending ou une erreur après soumission conserve le ticket pour
vérifier le résultat. Le mod ne renvoie jamais CREATE automatiquement. Les clics
périmés et les changements de partie invalident l'aperçu. Un résultat Partial
reste explicitement distinct d'une réussite complète.

## Organisation

Tous les fichiers Kotlin de production sont dans `src/main/kotlin` :

- `Entry.kt` déclare le mod et son service optionnel.
- `fr/nimby/placement/Network.kt` décrit les données géométriques du calcul.
- `fr/nimby/placement/PlacementPlanner.kt` calcule les espacements et les arrêts.
- `fr/nimby/placement/tool/NativeNetwork.kt` adapte les observations du SDK.
- `fr/nimby/placement/tool/PlacementWorkflow.kt` gère le panneau, l'aperçu,
  la confirmation, les tickets, l'annulation et le changement de partie.
- `src/test/kotlin` vérifie le calcul, le parcours et les callbacks Kotlin/natifs.
- `mod.json` définit l'identité et la compatibilité. Le plugin fournit `modInfo`
  à Kotlin ; `metadata` dans `Entry.kt` fournit l'auteur et la description.
  Le `mod.txt` du jeu est généré automatiquement au build, jamais maintenu dans assets.

Les offsets, appels du jeu et détails Windows appartiennent au SDK.

## Construire et vérifier

Avec JDK 21 et le kit SDK Kotlin construit depuis les sources correspondantes :

```powershell
.\gradlew.bat windowsTest verifyNativeMod -PnrfSdkDir=C:/chemin/kit-kotlin
.\gradlew.bat assembleReleaseMod -PnrfSdkDir=C:/chemin/kit-kotlin
```

Dans le workspace IntelliJ, utiliser **NRF - Pose de signaux - Compiler le mod**
et **NRF - Pose de signaux - Tester le mod**. Ces configurations préparent le kit SDK
local puis compilent le mod ; elles ne lancent pas le jeu.

Le paquet se trouve dans `build/gradle/mod/release`. Le SDK et ses ponts de
construction/UI doivent correspondre au même build. Le kit alpha.1 déjà publié
précède cette API. Les commandes ci-dessus n'installent rien dans une partie.

Le journal natif commun `logs/mods` reçoit les actions, le signal source, la
session, l'espacement, le ticket et les résultats. Racine par défaut :
`%LOCALAPPDATA%/NimbyRailsFrance/`, remplaçable par `NRF_LOG_DIR`.

Le VPS est réservé à la production. Voir [la validation](docs/validation.md)
et [le contrat d'intégration](docs/sdk-integration.md) pour les limites actuelles.

Publication GitHub pendant une indisponibilité du VPS : commit de release avec
le trailer `Release-Runner: github`. Les binaires Windows sont construits et
testés par GitHub Actions, puis le catalogue public du Hub est actualisé.
Le SDK épinglé doit être publié avant de lancer cette release.
