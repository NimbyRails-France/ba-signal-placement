# Changelog

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
