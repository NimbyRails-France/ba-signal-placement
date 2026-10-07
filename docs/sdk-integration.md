# Intégration du mod de pose au SDK

Le projet est un mod Kotlin/Native Windows. Il déclare le service
`repeat.v1` et fonctionne sans dépendance obligatoire au mod de signalisation.
Un modèle de signal peut proposer une action avec `whenMod = "signal-placement"`.
Le SDK montre le bouton seulement si le service observe la même partie.

## Répartition des rôles

- Le SDK fournit le contexte, les observations du réseau, les boutons, les
  journaux et le pont expérimental de construction Windows.
- Le noyau de BA Signal Placement calcule les positions selon l'espacement, les
  métriques disponibles, les connexions et les jonctions observées.
- `PlacementWorkflow` gère l'aperçu, la confirmation, le ticket et l'annulation.
- `NativeNetwork` adapte les données génériques au noyau géométrique de pose.
  Il ne contient aucun offset du jeu ni règle de signalisation nationale.

Le planificateur lit une vue indexée du réseau. L'adaptateur conserve la
topologie validée du SDK et convertit seulement les voies et les signaux du
parcours demandé. Son index de signaux est construit une fois par capture :
parcourir plusieurs voies ne rescane pas tous les signaux de la carte.
Les observations ne sont pas réutilisées entre deux calculs : la confirmation
repart toujours d'une capture obtenue après la préparation du ticket.

Un tick sans action ne recapture pas le réseau. Il renouvelle le dessin et le
panneau avec leurs données déjà calculées ; les contrôles sont reconstruits
seulement si leur état affiché change. Une nouvelle distance recalcule l'aperçu
immédiatement, tandis qu'une valeur identique conserve l'approbation courante.

Le panneau permet de saisir l'espacement, afficher ou masquer les signaux
temporaires sur la carte, confirmer la pose et annuler la série.
`showSignalPreview` transmet au SDK les positions calculées par le mod.
`clearSignalPreview` retire cet aperçu sans toucher aux signaux construits.
Le worker renouvelle la publication ; sans renouvellement elle expire en deux
secondes. Le SDK la lie au fournisseur, au panneau et à la génération de partie.
Un retour réussi signifie que la demande de dessin est acceptée, pas que toutes
les positions se trouvent dans la zone et la couche actuellement visibles.
Les boutons et messages sont servis par le SDK depuis le worker du mod.

## Contrat d'une opération

La prévisualisation ne construit rien. Après confirmation, le mod prépare un
ticket, relit le réseau et recalcule l'aperçu. Un changement impose une nouvelle
confirmation. Une commande incertaine ou Pending conserve son ticket : le mod
interroge son état sans renvoyer une création. Un changement de partie invalide
l'aperçu. Les résultats partiels et refusés sont affichés et journalisés.

Le panneau du mod est l'unique interface de BA Signal Placement. Les fonctions du
pont de construction restent expérimentales ; les essais unitaires ne qualifient
pas le jeu réel.

## Vérifications restantes dans une partie Windows

- Mesures sur courbes, dans les deux sens, et complétude des jonctions observées.
- Aiguilles en pointe, en talon et aux limites d'un segment.
- Reproduction du modèle, du sens, des textures et des réglages de la source.
- Changement ou suppression de la source et changement de partie pendant un aperçu.
- Refus partiel, arrêt du jeu ou du mod, puis suivi d'un ticket incertain.
- Annulation, sauvegarde/rechargement et identité des signaux créés.
- Lisibilité du panneau et journaux de succès comme d'échec.
- Dessins temporaires sur droite/courbe, dans les deux sens et sur chaque couche ;
  disparition à la saisie, au masquage et avant la pose, sans persistance en sauvegarde.

Les tests automatisés locaux couvrent le planificateur, le cycle aperçu/pose,
les refus, les tickets incertains et le pont de callbacks Kotlin. Ils ne lancent
pas le jeu. Aucun build ni test n'est exécuté sur le VPS de production.
Les limites techniques sont suivies dans le dossier de recherche du SDK.
