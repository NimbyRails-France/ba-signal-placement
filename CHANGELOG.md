# Changelog

## [0.1.0] - 2026-10-08

- Première version stable sous le nom **BA Signal Placement**.
- Rend le calcul des emplacements plus efficace sur les grandes cartes.
- Conserve l'aperçu lorsque l'espacement reste identique et le met à jour dès que la valeur change.
- Améliore la reprise après une attente temporaire : la pose reste désactivée tant que l'aperçu n'est pas prêt.
- Renforce la protection contre les poses en double et les confirmations tardives après la fermeture ou le masquage de l'aperçu.

Prérequis : **SDK 0.9.0-alpha.1** (alpha) ou plus récent, inférieur à **0.10.0**.

English:

- First stable release under the name **BA Signal Placement**.
- Makes placement calculations more efficient on large maps.
- Preserves the preview when spacing is unchanged and updates it as soon as the value changes.
- Improves recovery from temporary delays: placement remains disabled until the preview is ready.
- Strengthens protection against duplicate placement and late confirmations after closing or hiding the preview.

Requires **SDK 0.9.0-alpha.1** (alpha) or later, below **0.10.0**.

## [0.1.0-alpha.3] - 2026-09-28

- Utilise le SDK 0.8.0-alpha.5 : les signaux répétés héritent des réglages NRF, y compris les cases décochées.
- Les rafraîchissements d’un ancien panneau ne provoquent plus de perte d’observation ni d’erreurs répétées.
- Le SDK du jeu doit être mis à jour avec ce mod. La vérification visuelle en jeu reste nécessaire.

English:

- Uses SDK 0.8.0-alpha.5: repeated signals inherit NRF checkbox settings, including unchecked values.
- Stale panel refreshes no longer trigger observation loss or repeated errors.
- Requires updating the game SDK together with this mod; visual verification in game remains necessary.

## [0.1.0-alpha.2] - 2026-09-28

- Reconstruction avec le SDK 0.8.0-alpha.4 et son adaptateur de lecture légère de la session : l'outil au repos ne relit plus tout le réseau à chaque cycle.
- Réduction de la contention avec les observations de signalisation, susceptible de retarder l'ouverture des carrés à l'approche des trains.
- Le réseau complet reste lu à la demande pour calculer les emplacements des signaux.

## [0.1.0-alpha.1] - 2026-09-28

- Répéter un signal depuis son panneau lorsque le mod de signalisation propose le service facultatif.
- Espacement saisi en mètres, aperçu de tous les emplacements proposés, confirmation et annulation par ticket.
- Arrêt du parcours devant les aiguilles ou une géométrie ambiguë ; aucune sélection automatique de branche.
- Bouton Fermer conservant l’espacement, les opérations en cours et la possibilité d’annuler.
- Panneau stable pendant les lectures lentes, défilement et textes en français/anglais selon la langue du jeu.
- Windows uniquement, SDK 0.8.0-alpha.3 requis. Version alpha pour une partie solo ; valider sur une sauvegarde dédiée.
