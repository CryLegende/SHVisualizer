package org.Cry_Legende.sHVisualizer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Skull;
import org.bukkit.block.data.type.NoteBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Bloc musical : sa note est écrite un instant, du côté où le joueur l'a touché. Les notes jouées par la redstone ne montrent rien. */
final class Notes implements Listener {

    private final SHVisualizer plugin;
    private final Moteur moteur;
    private final Effets effets;
    private final Map<Block, Scene> affichees = new HashMap<>();

    Notes(SHVisualizer plugin, Moteur moteur, Effets effets) {
        this.plugin = plugin;
        this.moteur = moteur;
        this.effets = effets;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.NOTE_BLOCK || e.getHand() != EquipmentSlot.HAND
                || e.useInteractedBlock() == Event.Result.DENY || !plugin.reglages().notes().actif()) {
            return;
        }
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_BLOCK && a != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        Player p = e.getPlayer();
        // accroupi avec un objet en main : il pose un bloc contre le bloc musical, il ne le règle pas
        if (a == Action.RIGHT_CLICK_BLOCK && p.isSneaking() && !p.getInventory().getItemInMainHand().getType().isAir()) {
            return;
        }
        BlockFace face = e.getBlockFace();
        moteur.plusTard(() -> montrer(p, b, face));
    }

    private void montrer(Player p, Block b, BlockFace face) {
        Reglages reg = plugin.reglages();
        Reglages.Notes n = reg.notes();
        if (b.getType() != Material.NOTE_BLOCK || !(b.getBlockData() instanceof NoteBlock donnees)
                || reg.joueurs().mondesIgnores().contains(b.getWorld().getName()) || effets.discret(p)) {
            return;
        }
        Textes textes = plugin.textes();
        Block dessus = b.getRelative(BlockFace.UP);
        Component texte;
        if (dessus.getState(false) instanceof Skull) {
            texte = textes.avec("bloc-musical.tete", "tete", Component.translatable(dessus.getType().translationKey()));
        } else {
            String instrument = textes.texte("bloc-musical.instruments." + donnees.getInstrument().name());
            if (instrument.isEmpty()) {
                instrument = donnees.getInstrument().name().toLowerCase(Locale.ROOT).replace('_', ' ');
            }
            int id = donnees.getNote().getId();
            List<String> noms = textes.liste("bloc-musical.notes");
            String nom = id >= 0 && id < noms.size() ? noms.get(id) : Integer.toString(id);
            texte = Textes.composant(textes.texte("bloc-musical.format", "instrument", instrument))
                    .replaceText(c -> c.matchLiteral("{note}").replacement(Component.text(nom, couleur(id))));
        }
        double x = b.getX() + 0.5 + face.getModX() * n.distance();
        double y = b.getY() + 0.4 + face.getModY() * n.distance();
        double z = b.getZ() + 0.5 + face.getModZ() * n.distance();
        Scene ancienne = affichees.remove(b);
        if (ancienne != null) {
            ancienne.fermer();
        }
        Scene s = new Scene(moteur, b.getWorld(), x, y, z, n.portee(), Scene.LIBRE);
        Affichage a = Affichage.texte(moteur.pont, b.getWorld(), x, y, z);
        Poses.etiquette(a.texte(), n.taille(), false, reg.etiquettes());
        a.texte().text(texte);
        s.parties.add(a);
        moteur.jouer(s);
        if (s.spectateurs.isEmpty()) {
            s.fermer();
            return;
        }
        affichees.put(b, s);
        moteur.apres(n.duree(), () -> {
            affichees.remove(b, s);
            s.fermer();
        });
    }

    /** La couleur que le jeu donne à la particule de cette note. */
    private static TextColor couleur(int note) {
        float f = note / 24f;
        return TextColor.color(composante(f), composante(f + 1f / 3f), composante(f + 2f / 3f));
    }

    private static int composante(float f) {
        return Math.round(Math.max(0f, (float) Math.sin(f * Math.PI * 2) * 0.65f + 0.35f) * 255f);
    }
}
