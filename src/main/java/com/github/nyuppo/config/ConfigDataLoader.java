package com.github.nyuppo.config;

import com.github.nyuppo.MoreMobVariants;
import com.github.nyuppo.variant.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.Biome;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ConfigDataLoader implements SimpleSynchronousResourceReloadListener {
    private final Identifier SETTINGS_ID = new Identifier(MoreMobVariants.MOD_ID, "settings/settings.json");

    @Override
    public Identifier getFabricId() {
        return new Identifier(MoreMobVariants.MOD_ID, MoreMobVariants.MOD_ID);
    }

    @Override
    public void reload(ResourceManager manager) {
        MoreMobVariants.LOGGER.info("Reloading config...");

        Variants.clearAllVariants();
        for (Identifier id : manager.findResources("variants", path -> path.getPath().endsWith(".json")).keySet()) {
            String path = id.getPath().substring("variants/".length(), id.getPath().length() - ".json".length());
            String[] split = path.split("/");
            // mob id is stored in split[0] (i.e. "cow"), variant id is stored in split[1] (i.e. "dairy")
            // this pattern repeats a lot throughout this class and it's related classes, so hopefully you see this comment before all of that lol

            if (manager.getResource(id).isPresent()) {
                try (InputStream stream = manager.getResource(id).get().getInputStream()) {
                    applyVariant(new InputStreamReader(stream, StandardCharsets.UTF_8), id.getNamespace(), split[0], split[1]);
                } catch (Exception e) {
                    MoreMobVariants.LOGGER.error("Error occured while loading " + split[0] + " variant '" + split[1] + "' (" + id.toShortTranslationKey() + ")", e);
                }
            } else {
                MoreMobVariants.LOGGER.error(id.toShortTranslationKey() + " was not present.");
            }
        }
        Variants.validateEmptyVariants();

        VariantBlacklist.clearAllBlacklists();
        for (Identifier id : manager.findResources("blacklist", path -> path.getPath().endsWith(".json")).keySet()) {
            String mob = id.getPath().substring("blacklist/".length(), id.getPath().length() - ".json".length());

            if (manager.getResource(id).isPresent()) {
                try (InputStream stream = manager.getResource(id).get().getInputStream()) {
                    applyBlacklist(new InputStreamReader(stream, StandardCharsets.UTF_8), mob);
                } catch (Exception e) {
                    MoreMobVariants.LOGGER.error("Error occured while loading blacklist config " + id.toShortTranslationKey(), e);
                    VariantBlacklist.clearBlacklist(Variants.getMob(mob));
                }
            } else {
                MoreMobVariants.LOGGER.error(id.toShortTranslationKey() + " was not present.");
            }
        }
        Variants.applyBlacklists();

        Optional<Resource> settings = manager.getResource(SETTINGS_ID);
        if (settings.isPresent()) {
            try (InputStream stream = manager.getResource(SETTINGS_ID).get().getInputStream()) {
                applySettings(new InputStreamReader(stream, StandardCharsets.UTF_8));
            } catch (Exception e) {
                MoreMobVariants.LOGGER.error("Error occured while loading settings config " + SETTINGS_ID.toShortTranslationKey(), e);
                VariantSettings.resetSettings();
            }
        }
    }

    private void applyVariant(Reader reader, String namespace, String mobId, String variantId) {
        JsonElement element = JsonParser.parseReader(reader);

        int weight = 0;
        List<VariantModifier> modifiers = new ArrayList<>();

        String variantName = variantId;

        if (element.getAsJsonObject().size() != 0) {
            if (element.getAsJsonObject().has("weight")) {
                weight = element.getAsJsonObject().get("weight").getAsInt();
            } else {
                MoreMobVariants.LOGGER.error("Variant " + namespace + ":" + mobId + "/" + variantId + " has no weight, skipping.");
                return;
            }

            if (element.getAsJsonObject().has("name")) {
                variantName = element.getAsJsonObject().get("name").getAsString();
            }

            if (element.getAsJsonObject().has("shiny")) {
                if (element.getAsJsonObject().get("shiny").getAsBoolean()) {
                    modifiers.add(new ShinyModifier());
                }
            }

            if (element.getAsJsonObject().has("discard_chance")) {
                modifiers.add(new DiscardableModifier(element.getAsJsonObject().get("discard_chance").getAsDouble()));
            }

            if (element.getAsJsonObject().has("biome_tag")) {
                String[] biomesIdentifier = element.getAsJsonObject().get("biome_tag").getAsString().split(":");
                TagKey<Biome> biomes = TagKey.of(RegistryKeys.BIOME, new Identifier(biomesIdentifier[0], biomesIdentifier[1]));
                modifiers.add(new SpawnableBiomesModifier(biomes));
            }

            if (element.getAsJsonObject().has("breeding")) {
                JsonElement breeding = element.getAsJsonObject().get("breeding");
                if (breeding.getAsJsonObject().has("parent1") &&
                        breeding.getAsJsonObject().has("parent2") &&
                        breeding.getAsJsonObject().has("breeding_chance")) {
                    String[] parent1 = breeding.getAsJsonObject().get("parent1").getAsString().split(":");
                    String[] parent2 = breeding.getAsJsonObject().get("parent2").getAsString().split(":");
                    double breedingChance = breeding.getAsJsonObject().get("breeding_chance").getAsDouble();

                    modifiers.add(new BreedingResultModifier(
                            new Identifier(parent1[0], parent1[1]),
                            new Identifier(parent2[0], parent2[1]),
                            breedingChance));
                }
            }

            if (element.getAsJsonObject().has("custom_wool")) {
                if (element.getAsJsonObject().get("custom_wool").getAsBoolean()) {
                    modifiers.add(new CustomWoolModifier());
                }
            }

            if (element.getAsJsonObject().has("custom_eyes")) {
                if (element.getAsJsonObject().get("custom_eyes").getAsBoolean()) {
                    modifiers.add(new CustomEyesModifier());
                }
            }
        }

        Variants.addVariant(Variants.getMob(mobId), new MobVariant(new Identifier(namespace, variantName), weight, modifiers));
    }

    private void applyBlacklist(Reader reader, String mob) {
        JsonElement element = JsonParser.parseReader(reader);

        if (element.getAsJsonObject().size() != 0) {
            if (element.getAsJsonObject().has("blacklist")) {
                JsonArray blacklist = element.getAsJsonObject().get("blacklist").getAsJsonArray();
                for (JsonElement entry : blacklist) {
                    String[] entrySplit = entry.getAsString().split(":");
                    VariantBlacklist.blacklist(Variants.getMob(mob), new Identifier(entrySplit[0], entrySplit[1]));
                }
            }
        }
    }

    private void applySettings(Reader reader) {
        JsonElement element = JsonParser.parseReader(reader);

        if (element.getAsJsonObject().size() != 0) {
            if (element.getAsJsonObject().has("enable_muddy_pigs")) {
                VariantSettings.setEnableMuddyPigs(element.getAsJsonObject().get("enable_muddy_pigs").getAsBoolean());
            }
            if (element.getAsJsonObject().has("muddy_pig_timeout")) {
                VariantSettings.setMuddyPigTimeout(element.getAsJsonObject().get("muddy_pig_timeout").getAsInt());
            }
            if (element.getAsJsonObject().has("child_random_variant_chance")) {
                VariantSettings.setChildRandomVariantChance(element.getAsJsonObject().get("child_random_variant_chance").getAsDouble());
            }
        }
    }
}
