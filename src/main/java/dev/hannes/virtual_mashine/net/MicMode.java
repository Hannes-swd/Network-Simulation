package dev.hannes.virtual_mashine.net;

/** What a microphone block picks up: the players' real voices, the sounds of the game world around it, or both. */
public enum MicMode {
    VOICE,
    VOICE_AND_GAME,
    GAME;

    public boolean hearsVoices() {
        return this != GAME;
    }

    public boolean hearsGame() {
        return this != VOICE;
    }

    public String translationKey() {
        return "virtual_mashine.mic.mode." + name().toLowerCase(java.util.Locale.ROOT);
    }

    public static MicMode byIndex(int index) {
        MicMode[] values = values();
        return index >= 0 && index < values.length ? values[index] : VOICE;
    }
}
