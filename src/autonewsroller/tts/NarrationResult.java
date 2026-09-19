package autonewsroller.tts;
import java.nio.file.Path;
public record NarrationResult(String engine,String voice,Path wav,Path metadata) {}
