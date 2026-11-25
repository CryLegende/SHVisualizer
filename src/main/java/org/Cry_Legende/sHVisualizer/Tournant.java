package org.Cry_Legende.sHVisualizer;

/**
 * Fait tourner un objet flottant sur lui-même. Le serveur n'envoie qu'un angle de temps en temps ; le jeu du joueur
 * tourne l'objet jusque-là, sans rien recevoir entre deux. Rien n'est envoyé tant que personne ne regarde.
 */
final class Tournant {

    private static final float TOUR = (float) (Math.PI * 2);

    private final Moteur moteur;
    private final Scene scene;
    private final Affichage affichage;
    private final Reglages.Flottant reglage;
    private final float taille;
    float angle;
    /** Hauteur ajoutée à la pose : l'objet de la table d'enchantement monte et redescend. */
    float hauteur;
    /** En pause pendant qu'une autre animation déplace l'objet : deux glissements à la fois se gêneraient. */
    boolean pause;
    boolean fini;

    Tournant(Moteur moteur, Scene scene, Affichage affichage, Reglages.Flottant reglage, float angle, float taille) {
        this.moteur = moteur;
        this.scene = scene;
        this.affichage = affichage;
        this.reglage = reglage;
        this.angle = angle;
        this.taille = taille;
        if (reglage.rotation() > 0) {
            moteur.apres(2, this::pas);
        }
    }

    private void pas() {
        if (fini || scene.fermee) {
            return;
        }
        if (!pause && !scene.spectateurs.isEmpty()) {
            angle = (angle + (float) Math.toRadians(reglage.rotation() * reglage.pas())) % TOUR;
            affichage.display().setTransformation(Poses.flottant(0, hauteur, 0, angle, taille));
            affichage.relancer(reglage.pas());
            scene.envoyer(affichage);
        }
        moteur.apres(reglage.pas(), this::pas);
    }
}
