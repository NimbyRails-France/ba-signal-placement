# Validation du mod BA Signal Placement

La validation actuelle porte sur le mod Kotlin/Native Windows. Les anciennes
recettes de la fenêtre Swing ne valident pas le panneau intégré au jeu.

## Tests locaux automatisés

Depuis la racine de BA Signal Placement, avec JDK 21 et `nrfSdkDir` configuré :

```powershell
.\gradlew.bat windowsTest verifyNativeMod
```

- `PlacementPlannerTest` : espacement curviligne, sens, raccords, aiguilles,
  voies inconnues, emplacements occupés et limites du parcours.
- `PlacementWorkflowTest` : aperçu, confirmation, annulation, changement de
  réseau ou de partie, refus, résultat partiel et ticket incertain.
- `ToolBridgeTest` : transmission des actions et du panneau à travers les
  callbacks Kotlin/natifs, actualisation et rejet des clics périmés.
- `PlacementPerformanceTest` : renouvellements sans capture ni reconstruction
  des contrôles, réaction immédiate à une nouvelle distance, invariance du
  résultat avec 20 000 voies et signaux sans rapport avec le parcours. Les
  durées affichées servent au diagnostic ; les assertions portent sur le
  travail effectué et le résultat, sans seuil dépendant de la machine.
- `verifyNativeMod` : chargement de la DLL, exports, démarrage, arrêt et rechargement.

Ces tests utilisent des observations contrôlées et un hôte de chargement local.
Ils ne démarrent pas le jeu. Les contrats natifs de construction restent testés
séparément dans le SDK. Aucun test ni compilation n'est lancé sur le VPS de
production ; l'ancien workflow de l'application externe a été retiré.

## Recette dans une partie Windows

Elle reste à effectuer avec le SDK et le mod issus du même build :

1. Charger BA Signal Placement avec un mod de signaux compatible et vérifier le
   bouton **Répéter ce signal**. Vérifier aussi le mod de signaux sans cet outil.
2. Vérifier les distances sur une droite puis une courbe, dans les deux sens.
3. Vérifier les arrêts aux aiguilles en pointe et en talon, y compris aux raccords.
4. Calculer un aperçu : contrôler les signaux temporaires, leur sens et leur
   position sur la carte. Modifier l'espacement et vérifier le recalcul, puis
   masquer l'aperçu. Aucun de ces gestes ne doit créer de signal persistant.
5. Confirmer la pose et contrôler modèle, sens, textures et réglages des copies.
6. Modifier ou supprimer la source entre aperçu et confirmation : l'ancienne
   préparation doit être refusée ou demander une nouvelle confirmation.
7. Annuler la dernière série et contrôler les signaux conservés.
8. Changer de partie et vérifier l'invalidation des anciens boutons et tickets.
9. Sauvegarder/recharger puis vérifier les signaux créés et les journaux.

Les cas incertains, refus partiels et interruptions nécessitent également une
recette contrôlée. Les limites du [contrat](sdk-integration.md) s'appliquent :
les tests locaux ne prouvent pas la complétude des jonctions lues dans le jeu.

Les régressions automatisées couvrent aussi le renouvellement de l'aperçu,
son retrait avant construction, le changement de partie et les erreurs de
publication. Le SDK vérifie sur fixtures l'absence d'écriture dans les pools,
les identités complètes, les couches visibles, l'orientation et l'expiration.
Le test optionnel sur le binaire reconnu vérifie la signature d'entrée du rendu ;
il ne lance pas le jeu et ne constitue pas une validation visuelle.
