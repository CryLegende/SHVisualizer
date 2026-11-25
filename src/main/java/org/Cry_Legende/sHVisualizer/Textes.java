package org.Cry_Legende.sHVisualizer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.List;

/**
 * Textes de messages.yml. Le fichier du jar sert de valeurs par défaut : une clé absente du fichier du serveur garde le
 * texte du jar, une valeur vide posée exprès reste muette.
 */
final class Textes {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexCharacter('#').hexColors().build();

    private final YamlConfiguration fichier;

    Textes(YamlConfiguration fichier) {
        this.fichier = fichier;
    }

    /** Envoie chat.« cle » ; un message vide n'est pas envoyé. */
    void envoyer(CommandSender a, String cle, String... remplacements) {
        String texte = texte("chat." + cle, remplacements);
        if (!texte.isEmpty()) {
            a.sendMessage(composant(texte));
        }
    }

    String texte(String cle, String... remplacements) {
        String texte = fichier.getString(cle);
        if (texte == null) {
            return "";
        }
        for (int i = 0; i + 1 < remplacements.length; i += 2) {
            texte = texte.replace("{" + remplacements[i] + "}", remplacements[i + 1]);
        }
        return texte;
    }

    List<String> liste(String cle) {
        return fichier.getStringList(cle);
    }

    static Component composant(String texte) {
        return LEGACY.deserialize(texte);
    }

    /** Le texte de « cle », où {nom} est remplacé par un composant (un nom traduit par le jeu du joueur, par exemple). */
    Component avec(String cle, String nom, Component valeur) {
        return composant(texte(cle)).replaceText(TextReplacementConfig.builder().matchLiteral("{" + nom + "}").replacement(valeur).build());
    }

    /** Couleur que donne un code « &b » ou « &#RRGGBB » ; blanc si le texte n'en est pas un. */
    TextColor couleur(String cle) {
        TextColor c = composant(texte(cle) + "x").color();
        return c == null ? TextColor.color(0xFFFFFF) : c;
    }
}
