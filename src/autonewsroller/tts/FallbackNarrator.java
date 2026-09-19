package autonewsroller.tts;

import java.nio.file.Path;
public final class FallbackNarrator implements NarrationEngine {private final NarrationEngine primary,fallback;public FallbackNarrator(NarrationEngine primary,NarrationEngine fallback){this.primary=primary;this.fallback=fallback;}public String name(){return primary.name()+" -> "+fallback.name();}public NarrationResult narrate(String text,String voice,Path output)throws Exception{try{return primary.narrate(text,voice,output);}catch(Exception e){System.err.println("Kokoro failed: "+e.getMessage());System.err.println("Activating Qwen3-TTS fallback.");return fallback.narrate(text,voice,output);}}}
