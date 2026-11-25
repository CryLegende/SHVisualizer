package org.Cry_Legende.sHVisualizer;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.TextDisplay;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Une entité que seuls des joueurs voient : elle est fabriquée par le serveur mais jamais posée dans le monde, donc ni
 * tick, ni suivi, ni sauvegarde. On la règle comme n'importe quelle entité, puis on envoie aux joueurs ce qui a changé.
 * Elle garde le même numéro toute sa vie : remontrée à un joueur, elle remplace l'ancienne chez lui.
 */
final class Affichage {

    private final Pont pont;
    private final Entity entite;
    private final Object handle;
    final int id;
    private final UUID uuid;
    final double x, y, z;
    private final float pitch;
    private final int donnee;
    private List<Object> apparition;

    private Affichage(Pont pont, Entity entite, double x, double y, double z, float pitch, int donnee) {
        this.pont = pont;
        this.entite = entite;
        this.handle = pont.handle(entite);
        this.id = entite.getEntityId();
        this.uuid = entite.getUniqueId();
        this.x = x;
        this.y = y;
        this.z = z;
        this.pitch = pitch;
        this.donnee = donnee;
    }

    static Affichage objet(Pont pont, World monde, double x, double y, double z) {
        return new Affichage(pont, monde.createEntity(new Location(monde, x, y, z), ItemDisplay.class), x, y, z, 0f, 0);
    }

    static Affichage texte(Pont pont, World monde, double x, double y, double z) {
        return new Affichage(pont, monde.createEntity(new Location(monde, x, y, z), TextDisplay.class), x, y, z, 0f, 0);
    }

    /** Cadre invisible couché sur le dessus d'un bloc : le seul moyen de montrer le dessin d'une carte. */
    static Affichage cadre(Pont pont, World monde, double x, double y, double z) {
        ItemFrame cadre = monde.createEntity(new Location(monde, x, y, z), ItemFrame.class);
        pont.horsDuMonde(cadre);
        cadre.setFacingDirection(BlockFace.UP, true);
        cadre.setVisible(false);
        cadre.setSilent(true);
        // 1 : la face du dessus, comme le client l'attend dans le paquet d'apparition
        return new Affichage(pont, cadre, x, y, z, -90f, 1);
    }

    ItemDisplay objet() {
        return (ItemDisplay) entite;
    }

    TextDisplay texte() {
        return (TextDisplay) entite;
    }

    Display display() {
        return (Display) entite;
    }

    ItemFrame cadre() {
        return (ItemFrame) entite;
    }

    boolean estCadre() {
        return entite instanceof ItemFrame;
    }

    /**
     * Ajoute à « dans » ce qu'il faut envoyer à un joueur qui découvre l'entité : son apparition, puis son état complet.
     * Les deux sont gardés jusqu'au prochain changement : dix joueurs qui arrivent reçoivent les mêmes.
     */
    void apparition(List<Object> dans) {
        if (apparition == null) {
            apparition = new ArrayList<>(2);
            apparition(apparition, x, y, z);
        }
        dans.addAll(apparition);
    }

    /** La même chose, à un autre endroit : pour une entité que chaque joueur voit à sa place. */
    void apparition(List<Object> dans, double x, double y, double z) {
        dans.add(pont.apparition(handle, id, uuid, x, y, z, pitch, 0f, donnee));
        List<?> valeurs = pont.valeurs(handle);
        if (!valeurs.isEmpty()) {
            dans.add(pont.donnees(id, valeurs));
        }
    }

    /** Ce qui a changé depuis le dernier envoi, null si rien. */
    Object paquetChangements() {
        List<?> c = pont.changements(handle);
        if (c == null || c.isEmpty()) {
            return null;
        }
        apparition = null;
        return pont.donnees(id, c);
    }

    /**
     * Fait repartir le glissement au prochain envoi : le client ne relance une interpolation que s'il reçoit son
     * délai, même inchangé. À appeler après avoir réglé la durée et la nouvelle pose.
     */
    void relancer(int duree) {
        Display d = display();
        d.setInterpolationDuration(duree);
        d.setInterpolationDelay(1);
        d.setInterpolationDelay(0);
    }
}
