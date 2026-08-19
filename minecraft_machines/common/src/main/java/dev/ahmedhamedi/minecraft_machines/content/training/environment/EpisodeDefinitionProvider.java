package dev.ahmedhamedi.minecraft_machines.content.training.environment;

@FunctionalInterface
public interface EpisodeDefinitionProvider {
    EpisodeDefinition nextEpisode(int slotIndex, long episodeIndex);
}
