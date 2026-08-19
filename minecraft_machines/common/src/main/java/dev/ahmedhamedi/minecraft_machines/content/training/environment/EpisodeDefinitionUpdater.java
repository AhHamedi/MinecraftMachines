package dev.ahmedhamedi.minecraft_machines.content.training.environment;

@FunctionalInterface
public interface EpisodeDefinitionUpdater {
    EpisodeDefinition update(int slot, EpisodeDefinition current);
}
