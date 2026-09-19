package autonewsroller.tts;
import java.nio.file.Path;
public interface NarrationEngine { String name(); NarrationResult narrate(String text,String voice,Path output) throws Exception; }
