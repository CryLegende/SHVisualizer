package org.Cry_Legende.sHVisualizer;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** La configuration livrée dans le jar se lit sans erreur ni avertissement, et dit ce qu'on attend d'elle. */
class ReglagesTest {

    private static YamlConfiguration jar() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                ReglagesTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
    }

    @Test
    void configParDefautValide() {
        List<String> avertissements = new ArrayList<>();
        Reglages r = Reglages.lire(jar(), jar(), avertissements::add);
        assertTrue(avertissements.isEmpty(), avertissements.toString());
        assertEquals(Reglages.Mode.POSE, r.tables().parBloc().get(Material.CRAFTING_TABLE).mode());
        assertEquals(9, r.tables().parBloc().get(Material.CRAFTING_TABLE).cases().size());
        assertEquals(Reglages.Mode.ENCHANTEMENT, r.tables().parBloc().get(Material.ENCHANTING_TABLE).mode());
        assertEquals(Reglages.Presentation.CARTE, r.tables().parBloc().get(Material.CARTOGRAPHY_TABLE).presentation());
        assertTrue(r.tables().parBloc().containsKey(Material.DAMAGED_ANVIL));
        assertTrue(r.conteneurs().parBloc().get(Material.CHEST).dessus());
        assertFalse(r.conteneurs().parBloc().get(Material.BARREL).dessus());
        assertTrue(r.conteneurs().parBloc().containsKey(Material.PURPLE_SHULKER_BOX));
        assertTrue(r.conteneurs().parBloc().containsKey(Material.SHULKER_BOX));
        // textes : debout, sans fond, avec ombre
        assertEquals(Display.Billboard.VERTICAL, r.etiquettes().orientation());
        assertEquals(0, r.etiquettes().fond().getAlpha());
        assertTrue(r.etiquettes().ombre());
    }

    @Test
    void blocsAPlat() {
        Reglages r = Reglages.lire(jar(), jar(), a -> {
        });
        // dessinés comme une image par le jeu
        for (Material m : List.of(Material.OAK_SAPLING, Material.CHERRY_DOOR, Material.OAK_HANGING_SIGN, Material.TORCH,
                Material.RAIL, Material.HOPPER, Material.RED_CANDLE, Material.IRON_BARS, Material.DEAD_BRAIN_CORAL_FAN)) {
            assertTrue(r.objets().aPlat().contains(m), m.name());
        }
        // dessinés en volume : un motif trop large les aurait pris
        for (Material m : List.of(Material.STONE, Material.SEA_LANTERN, Material.JACK_O_LANTERN, Material.MANGROVE_ROOTS,
                Material.BRAIN_CORAL_BLOCK, Material.OAK_TRAPDOOR, Material.CHEST, Material.WHITE_BANNER, Material.TORCHFLOWER_CROP)) {
            assertFalse(r.objets().aPlat().contains(m), m.name());
        }
    }

    @Test
    void valeurFausseNommee() {
        YamlConfiguration fichier = new YamlConfiguration();
        fichier.set("joueurs.periode", "dix");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Reglages.lire(fichier, jar(), a -> {
        }));
        assertTrue(e.getMessage().contains("joueurs.periode"), e.getMessage());
    }

    /** Ce qui ferait planter une animation en plein vol est refusé dès la lecture, en nommant la clé. */
    @Test
    void sonEtParticuleVerifies() {
        YamlConfiguration son = new YamlConfiguration();
        son.set("objets.vol.son", "ENTITY_ITEM_PICKUP");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Reglages.lire(son, jar(), a -> {
        }));
        assertTrue(e.getMessage().contains("objets.vol.son"), e.getMessage());

        YamlConfiguration particule = new YamlConfiguration();
        particule.set("tables.prise.particule", "DUST");
        e = assertThrows(IllegalArgumentException.class, () -> Reglages.lire(particule, jar(), a -> {
        }));
        assertTrue(e.getMessage().contains("tables.prise.particule"), e.getMessage());

        YamlConfiguration muet = new YamlConfiguration();
        muet.set("objets.vol.son", "");
        assertEquals("", Reglages.lire(muet, jar(), a -> {
        }).objets().vol().son());
        assertFalse(Reglages.lire(new YamlConfiguration(), jar(), a -> {
        }).conteneurs().parBloc().isEmpty());
        assertTrue(Reglages.lire(new YamlConfiguration(), jar(), a -> {
        }).conteneurs().menus());
    }

    @Test
    void cleAbsentePrendLeJar() {
        // un fichier de serveur plus vieux que le jar : ce qui lui manque vient du jar
        Reglages r = Reglages.lire(new YamlConfiguration(), jar(), a -> {
        });
        assertEquals(10, r.joueurs().periode());
        assertTrue(r.spawner().actif());
    }
}
