package org.Cry_Legende.sHVisualizer;

import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Ce qu'un joueur connecté voit en ce moment. Lu et modifié sur le fil principal seulement. */
final class Vue {

    final Player joueur;
    /** Joueur Bedrock (identifiant Floodgate) : son jeu ne dessine pas tout comme Java. */
    final boolean bedrock;
    /** Le joueur a coupé les affichages pour lui (/visuels). */
    boolean coupe;
    final Set<Scene> scenes = new HashSet<>();
    final int[] compte = new int[Scene.FAMILLES];
    /** Monde et endroit du dernier passage : rien n'est recalculé tant qu'il n'a pas bougé. */
    UUID monde;
    double px, py, pz;
    /** À repasser même sans bouger : arrivée, chunk pas encore reçu, réglage changé. */
    boolean aRevoir = true;
    /** Sa connexion, cherchée une fois : chaque envoi y va tout droit. */
    Object connexion;
    /** Case de la grille où il est rangé (un chunk), pour trouver vite qui est près d'un endroit ; null s'il n'y est pas. */
    java.util.List<Vue> rangement;
    /** Monde et chunk de cette case, pour la retirer de la grille quand elle se vide. */
    UUID rangementMonde;
    long rangementCle;
    /** Décale les passages des joueurs les uns par rapport aux autres. */
    final int tour;

    Vue(Player joueur, boolean coupe, int tour) {
        this.joueur = joueur;
        this.bedrock = joueur.getUniqueId().getMostSignificantBits() == 0L;
        this.coupe = coupe;
        this.tour = tour;
    }
}
