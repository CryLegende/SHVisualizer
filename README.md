<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHVisualizer

*Montre aux joueurs autour ce qui se passe dans les blocs, avec des display envoyés par paquets.*

<img src="https://img.shields.io/badge/Paper-26.3-f2a900?style=for-the-badge" alt="Paper 26.3"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

Remplace InteractionVisualizer sur SkyHand.

| Bloc | Affichage |
| --- | --- |
| Établi, enclume, meule, table de forgeron | Les objets des cases posés sur le bloc, le résultat qui flotte au-dessus. Quand le joueur le prend, les objets se rejoignent, un nuage, et le résultat vole vers lui. Sur l'enclume, le nom tapé s'écrit au-dessus. |
| Métier à tisser | La bannière dressée sur le bloc, avec le motif choisi. |
| Tailleur de pierre | L'objet dans la lame, puis la forme choisie. |
| Table de cartographie | La carte dessinée à plat sur la table. |
| Table d'enchantement | L'objet flotte ; à l'enchantement il monte, ses enchantements s'écrivent au-dessus, puis il redescend. |
| Coffre, double coffre, coffre de l'Ender, tonneau, boîte de Shulker, distributeur, dropper | Un objet vole du joueur vers le bloc quand il dépose, du bloc vers lui quand il prend. |
| Crafter | Sa grille sur le dessus, cases fermées comprises. |
| Spawner | Une barre qui se remplit jusqu'à la prochaine apparition, du côté du joueur. |
| Bannière nommée | Son nom au-dessus. |
| Jukebox | Le disque qui tourne au-dessus et son titre. |
| Bloc musical | L'instrument et la note, un instant, quand un joueur le touche. |

Chaque bloc se règle ou se coupe dans `config.yml`, et d'autres s'ajoutent sans toucher au code (un four comme un
coffre, par exemple) : les listes « blocs » des sections `tables` et `conteneurs` décrivent tout.

Le menu qu'un plugin ouvre quand on clique sur un de ces blocs de rangement (coffre agrandi, collecteur) est traité
comme le bloc lui-même : les objets y volent pareil, sans réglage propre à ce plugin (`conteneurs.menus-de-plugins`).

Les textes (nom d'une bannière, titre d'un disque, note, enchantements, nom sur l'enclume) sont écrits sans fond, avec
une ombre, et restent droits en se tournant vers le joueur : section `etiquettes` de `config.yml`.

Les gestes d'un joueur invisible (vanish, mode spectateur, coffre ouvert en silence avec OpenInv) ne montrent rien.
Chaque joueur peut couper les affichages pour lui avec `/visuels`.

Utilisé sur : **Opale**.

## Comment il travaille

- **Aucune entité sur le serveur.** Chaque affichage est un item display, un text display ou un cadre que le serveur
  fabrique sans le poser dans le monde ; seuls les joueurs proches en reçoivent les paquets. Rien à suivre, à faire
  tourner ni à sauvegarder.
- **Le client fait les mouvements.** Un objet qui vole, une barre qui se remplit, un disque qui tourne : le serveur envoie
  le point d'arrivée et la durée, le jeu du joueur anime. La barre d'un spawner coûte un message par apparition.
- **Seul ce qui est regardé est relu.** Une table est relue à chaque clic, et de temps en temps tant qu'elle reste
  ouverte, par sécurité. Un spawner, une bannière, un jukebox ou un crafter n'a de scène que tant qu'un joueur le voit,
  et seule cette scène relit son bloc. Un joueur qui ne bouge pas n'est pas recalculé.
- **Pas d'entité figée.** Un affichage garde le même numéro toute sa vie et son retrait est toujours envoyé : changement
  de monde, éloignement, déconnexion, rechargement, arrêt du plugin.
- **Rien n'est touché.** Le plugin ne change aucun inventaire, aucun bloc, aucun événement : il regarde et il dessine.
- **Pas de protocole dans le code.** C'est le serveur qui écrit les données de ses propres entités ; le plugin ne connaît
  ni numéros de champs ni format de paquet. Les quelques noms internes dont il a besoin sont cherchés au démarrage : s'il
  en manque un après une mise à jour du serveur, le plugin le dit dans la console et n'affiche rien, sans gêner le reste.

## Dépendances

- Optionnelle : `PlaceholderAPI` (`%shvisualizer_status%` vaut `enabled` ou `disabled` selon le choix du joueur, pour un
  bouton de menu)
- Optionnelle : `OpenInv` (un coffre ouvert en silence ne montre rien)

## Commandes

| Commande | Description | Permission |
|---|---|---|
| `/visuels` | Coupe ou remet les affichages pour soi (gardé d'une connexion à l'autre). | `shvisualizer.visuels` |
| `/shvisualizer reload` | Recharge `config.yml` et `messages.yml`. Un fichier invalide ne change rien, le message dit la clé en cause. | `shvisualizer.admin.reload` |
| `/shvisualizer info` | Affichages en cours, joueurs qui les voient, temps pris par tick et par poste, erreurs s'il y en a eu. | `shvisualizer.admin.info` |
| `/shvisualizer on\|off <joueur>` | Remet ou coupe les affichages d'un joueur connecté : ce qu'appelle un bouton de menu. | `shvisualizer.admin.on`, `shvisualizer.admin.off` |

`/interactionvisualizer` et `/iv` restent des alias de `/shvisualizer`.

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shvisualizer.visuels` | Couper ou remettre ses affichages avec `/visuels`. | `true` |
| `shvisualizer.admin` | Toute l'administration (parent des quatre suivantes). | `op` |
| `shvisualizer.admin.reload` | Recharger `config.yml` et `messages.yml`. | `op` |
| `shvisualizer.admin.info` | Voir l'état et le temps pris par le plugin. | `op` |
| `shvisualizer.admin.on` | Remettre les affichages d'un joueur. | `op` |
| `shvisualizer.admin.off` | Couper les affichages d'un joueur. | `op` |

## Configuration

<pre>
plugins/SHVisualizer/
├── <a href="src/main/resources/config.yml">config.yml</a>
└── <a href="src/main/resources/messages.yml">messages.yml</a>
</pre>

Les deux fichiers sont créés au premier démarrage. Une clé absente d'un fichier plus ancien que le jar prend la valeur
du jar. Le plugin se charge et se décharge à chaud.

## Compilation

Java 25 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`.
