package org.Cry_Legende.sHVisualizer;

import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Comment un objet se tient : posé sur un bloc, dressé, ou flottant. Le jeu dessine un objet « encadré » de face, tourné
 * vers le sud : une image fait un bloc de côté, un bloc un demi. Tout part de là.
 */
final class Poses {

    private static final float QUART = (float) (Math.PI / 2);
    /** Épaisseur d'une image d'objet, pour un côté de 1. */
    private static final float EPAISSEUR = 1f / 16f;

    private Poses() {
    }

    /**
     * Direction, parmi les quatre, dans laquelle le joueur regarde le bloc : {x, z}. C'est elle qui oriente tout ce qui
     * est posé, pour qu'il le voie comme dans son interface.
     */
    static int[] devant(Player joueur, double cx, double cz) {
        Location l = joueur.getLocation();
        double dx = cx - l.getX(), dz = cz - l.getZ();
        if (Math.abs(dx) < 0.05 && Math.abs(dz) < 0.05) {
            // debout sur le bloc : son regard décide
            double a = Math.toRadians(l.getYaw());
            dx = -Math.sin(a);
            dz = Math.cos(a);
        }
        return Math.abs(dx) > Math.abs(dz) ? new int[] {dx > 0 ? 1 : -1, 0} : new int[] {0, dz > 0 ? 1 : -1};
    }

    /** Angle, autour de la verticale, qui tourne vers le joueur un objet dessiné face au sud. */
    static float angle(int fx, int fz) {
        return (float) Math.atan2(-fx, -fz);
    }

    /**
     * Pose un objet sur une surface : une image à plat, le haut du dessin loin du joueur ; un bloc debout, sa face
     * vers lui. L'entité est au ras de la surface, la pose le soulève de sa demi-épaisseur.
     */
    static void poser(ItemDisplay d, ItemStack objet, boolean aPlat, float angle, Reglages.Objets o) {
        d.setItemStack(objet);
        d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
        if (aPlat) {
            float t = o.taillePlat();
            d.setTransformation(new Transformation(new Vector3f(0, t * EPAISSEUR / 2 + 0.002f, 0),
                    new Quaternionf().rotateY(angle).rotateX(-QUART), new Vector3f(t, t, t), new Quaternionf()));
        } else {
            float t = o.tailleVolume();
            d.setTransformation(new Transformation(new Vector3f(0, t / 2, 0), new Quaternionf().rotateY(angle),
                    new Vector3f(t * 2, t * 2, t * 2), new Quaternionf()));
        }
    }

    /** Dresse un objet sur une surface, face au joueur : la bannière du métier à tisser. */
    static void dresser(ItemDisplay d, ItemStack objet, float angle, float taille) {
        d.setItemStack(objet);
        d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
        d.setTransformation(new Transformation(new Vector3f(0, taille / 2, 0), new Quaternionf().rotateY(angle),
                new Vector3f(taille, taille, taille), new Quaternionf()));
    }

    /** Objet qui flotte, de la taille et de l'allure d'un objet tombé au sol. */
    static void flotter(ItemDisplay d, ItemStack objet, float angle, float taille) {
        d.setItemStack(objet);
        d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
        d.setTransformation(flottant(0, 0, 0, angle, taille));
    }

    static Transformation flottant(float tx, float ty, float tz, float angle, float taille) {
        return new Transformation(new Vector3f(tx, ty, tz), new Quaternionf().rotateY(angle), new Vector3f(taille, taille, taille), new Quaternionf());
    }

    /** La même pose, déplacée et mise à une autre taille : le but d'un glissement. */
    static Transformation vers(Transformation de, float tx, float ty, float tz, float facteur) {
        Vector3f taille = new Vector3f(de.getScale()).mul(facteur);
        return new Transformation(new Vector3f(tx, ty, tz), new Quaternionf(de.getLeftRotation()), taille, new Quaternionf(de.getRightRotation()));
    }

    /** Texte tourné vers le joueur, lisible de nuit ; son fond, son ombre et sa façon de tourner viennent des réglages. */
    static void etiquette(TextDisplay t, float taille, boolean aTravers, Reglages.Etiquettes style) {
        t.setBillboard(style.orientation());
        t.setBrightness(new Display.Brightness(15, 15));
        t.setSeeThrough(aTravers);
        t.setShadowed(style.ombre());
        t.setDefaultBackground(false);
        t.setBackgroundColor(style.fond());
        t.setLineWidth(400);
        t.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(taille, taille, taille), new Quaternionf()));
    }
}
