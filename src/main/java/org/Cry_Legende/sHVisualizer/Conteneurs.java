package org.Cry_Legende.sHVisualizer;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coffres et autres rangements : un objet vole du joueur vers le bloc quand il en dépose un, du bloc vers lui quand il
 * en prend. Le clic dit ce qui devrait bouger ; au tick suivant, on ne lance le vol que si la case a vraiment changé.
 */
final class Conteneurs implements Listener {

    /** Dernier bloc de rangement sur lequel le joueur a cliqué : c'est lui qu'un menu sans bloc vient d'ouvrir. */
    private record Clic(Block bloc, long quand) {
    }

    /**
     * Bloc d'où vient l'interface ouverte, quand elle ne le sait pas elle-même : le coffre de l'Ender, ou le menu qu'un
     * plugin ouvre à la place d'un coffre. « ferme » date sa fermeture : un menu qui se rouvre aussitôt (page suivante)
     * garde son bloc.
     */
    private record Origine(Block bloc, long ferme) {
    }

    /** Temps laissé entre le clic sur le bloc et l'ouverture du menu, et entre deux pages d'un même menu. */
    private static final long DELAI = 250_000_000L;
    /** Interfaces de rangement : les seules qu'un menu de plugin peut imiter ici. */
    private static final Set<InventoryType> RANGEMENTS = EnumSet.of(InventoryType.CHEST, InventoryType.ENDER_CHEST, InventoryType.BARREL,
            InventoryType.SHULKER_BOX, InventoryType.HOPPER, InventoryType.DISPENSER, InventoryType.DROPPER);

    private final SHVisualizer plugin;
    private final Moteur moteur;
    private final Effets effets;
    private final Map<UUID, Clic> clics = new ConcurrentHashMap<>();
    private final Map<UUID, Origine> origines = new ConcurrentHashMap<>();
    private final Map<UUID, Long> derniers = new HashMap<>();

    Conteneurs(SHVisualizer plugin, Moteur moteur, Effets effets) {
        this.plugin = plugin;
        this.moteur = moteur;
        this.effets = effets;
    }

    /**
     * Noté avant tous les autres plugins : celui qui ouvre son menu le fait pendant ce même événement, et l'ouverture
     * doit déjà trouver le clic. Un clic refusé ensuite n'ouvre rien, et s'oublie de lui-même.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b != null && e.getAction() == Action.RIGHT_CLICK_BLOCK && plugin.reglages().conteneurs().parBloc().containsKey(b.getType())) {
            clics.put(e.getPlayer().getUniqueId(), new Clic(b, System.nanoTime()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) {
            return;
        }
        UUID id = p.getUniqueId();
        Clic c = clics.remove(id);
        Inventory haut = e.getInventory();
        InventoryType type = haut.getType();
        Reglages.Conteneurs reg = plugin.reglages().conteneurs();
        // l'interface d'un vrai bloc dit elle-même où elle est ; les autres n'ont que le clic qui les a ouvertes
        if (!RANGEMENTS.contains(type) || Tables.bloc(haut, p) != null || type != InventoryType.ENDER_CHEST && !reg.menus()) {
            origines.remove(id);
            return;
        }
        long t = System.nanoTime();
        if (c != null && t - c.quand < DELAI) {
            origines.put(id, new Origine(c.bloc, 0));
            return;
        }
        // ouvert par une commande, sans bloc : rien ne volera. Sauf si c'est la page suivante du même menu.
        Origine o = origines.get(id);
        if (o != null && o.ferme != 0 && t - o.ferme < DELAI) {
            origines.put(id, new Origine(o.bloc, 0));
        } else {
            origines.remove(id);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        origines.computeIfPresent(e.getPlayer().getUniqueId(), (id, o) -> o.ferme == 0 ? new Origine(o.bloc, System.nanoTime()) : o);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        clics.remove(id);
        origines.remove(id);
        moteur.plusTard(() -> derniers.remove(id));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        Reglages.Conteneurs reg = plugin.reglages().conteneurs();
        if (!reg.actif() || !(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        InventoryView vue = e.getView();
        Inventory haut = vue.getTopInventory();
        int raw = e.getRawSlot();
        InventoryType type = haut.getType();
        // l'inventaire du joueur seul : le cas de presque tous les clics
        if (raw < 0 || type == InventoryType.CRAFTING || type == InventoryType.CREATIVE) {
            return;
        }
        boolean dansHaut = raw < haut.getSize();
        InventoryAction action = e.getAction();
        ItemStack vise = e.getCurrentItem();
        boolean plein = vise != null && !vise.getType().isAir();
        ItemStack objet;
        boolean entre;
        if (dansHaut) {
            switch (action) {
                case PICKUP_ALL, PICKUP_HALF, PICKUP_SOME, PICKUP_ONE, MOVE_TO_OTHER_INVENTORY -> {
                    objet = vise;
                    entre = false;
                }
                case PLACE_ALL, PLACE_SOME, PLACE_ONE, SWAP_WITH_CURSOR -> {
                    objet = e.getCursor();
                    entre = true;
                }
                case HOTBAR_SWAP -> {
                    // la touche d'un raccourci : la case se vide vers la barre, ou reçoit l'objet de la barre
                    if (plein) {
                        objet = vise;
                        entre = false;
                    } else {
                        int touche = e.getHotbarButton();
                        objet = touche >= 0 ? p.getInventory().getItem(touche) : p.getInventory().getItemInOffHand();
                        entre = true;
                    }
                }
                default -> {
                    return;
                }
            }
        } else if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            objet = vise;
            entre = true;
        } else {
            return;
        }
        if (objet == null || objet.getType().isAir() || !gere(haut, p, reg)) {
            return;
        }
        ItemStack montre = objet.clone();
        ItemStack avant = plein ? vise.clone() : null;
        moteur.plusTard(() -> confirmer(p, vue, raw, avant, montre, entre, dansHaut));
    }

    /** Un objet étalé sur plusieurs cases en tenant le clic : il entre dans le bloc si une des cases est en haut. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        Reglages.Conteneurs reg = plugin.reglages().conteneurs();
        if (!reg.actif() || !(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        InventoryView vue = e.getView();
        Inventory haut = vue.getTopInventory();
        int taille = haut.getSize();
        for (int raw : e.getRawSlots()) {
            if (raw < taille) {
                if (gere(haut, p, reg)) {
                    ItemStack montre = e.getOldCursor().clone();
                    ItemStack o = vue.getItem(raw);
                    ItemStack avant = o == null || o.getType().isAir() ? null : o.clone();
                    moteur.plusTard(() -> confirmer(p, vue, raw, avant, montre, true, true));
                }
                return;
            }
        }
    }

    /** Filtre rapide, sur le fil de l'événement : l'interface est-elle celle d'un bloc que l'on montre ? */
    private boolean gere(Inventory haut, Player p, Reglages.Conteneurs reg) {
        Block b = Tables.bloc(haut, p);
        if (b != null) {
            return reg.parBloc().containsKey(b.getType());
        }
        Origine o = origines.get(p.getUniqueId());
        return o != null && o.ferme == 0;
    }

    /** « geste » : le clic a touché le bloc lui-même, le bras du joueur bouge si l'objet part vraiment. */
    private void confirmer(Player p, InventoryView vue, int raw, ItemStack avant, ItemStack objet, boolean entre, boolean geste) {
        if (!p.isOnline() || p.getOpenInventory() != vue) {
            return;
        }
        ItemStack apres = vue.getItem(raw);
        if (apres != null && apres.getType().isAir()) {
            apres = null;
        }
        if (avant == null ? apres == null : apres != null && avant.isSimilar(apres) && avant.getAmount() == apres.getAmount()) {
            return;
        }
        Reglages reg = plugin.reglages();
        Reglages.Conteneurs c = reg.conteneurs();
        long t = moteur.tick();
        Long dernier = derniers.get(p.getUniqueId());
        if (dernier != null && t - dernier < c.pause()) {
            return;
        }
        Inventory haut = vue.getTopInventory();
        Reglages.Conteneur def;
        double bx, by, bz;
        Block reel = Tables.bloc(haut, p);
        if (reel != null) {
            // un double coffre répond le milieu de ses deux moitiés
            Location l = haut.getLocation();
            def = c.parBloc().get(reel.getType());
            bx = l.getX();
            by = l.getY();
            bz = l.getZ();
        } else {
            Origine o = origines.get(p.getUniqueId());
            if (o == null || o.ferme != 0 || o.bloc.getWorld() != p.getWorld()) {
                return;
            }
            Block b = o.bloc;
            Location ou = p.getLocation();
            double ex = b.getX() + 0.5 - ou.getX(), ey = b.getY() + 0.5 - ou.getY(), ez = b.getZ() + 0.5 - ou.getZ();
            // le joueur a été emmené ailleurs, menu resté ouvert : le bloc n'est plus devant lui
            if (ex * ex + ey * ey + ez * ez > c.portee() * c.portee()) {
                return;
            }
            def = c.parBloc().get(b.getType());
            bx = b.getX();
            by = b.getY();
            bz = b.getZ();
        }
        if (def == null || reg.joueurs().mondesIgnores().contains(p.getWorld().getName()) || effets.discret(p)) {
            return;
        }
        derniers.put(p.getUniqueId(), t);
        if (geste && reg.joueurs().geste()) {
            p.swingMainHand();
        }
        double[] j = effets.cible(p);
        double x = bx + 0.5, y, z = bz + 0.5;
        if (def.dessus()) {
            y = by + def.hauteur();
        } else {
            // sur le côté du bloc tourné vers le joueur
            y = by + 0.5;
            double dx = j[0] - x, dy = j[1] - y, dz = j[2] - z;
            double n = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (n > 0.01) {
                x += dx / n * def.distance();
                y += dy / n * def.distance();
                z += dz / n * def.distance();
            }
        }
        if (entre) {
            effets.vol(p.getWorld(), objet, j[0], j[1], j[2], x, y, z, x, y, z, c.portee(), false);
        } else {
            effets.vol(p.getWorld(), objet, x, y, z, j[0], j[1], j[2], x, y, z, c.portee(), false);
        }
    }
}
