package autonewsroller.orchestration;
import java.util.Locale;
/** Modes are explicit; unknown persisted values never silently downgrade. */
public enum ProductionMode {
    STANDARD, FRAMING_EXPOSURE, MEME_RAGEBAIT;
    public static ProductionMode fromPersisted(Object raw){
        if(raw==null||String.valueOf(raw).isBlank())return STANDARD;
        try{return valueOf(String.valueOf(raw).toUpperCase(Locale.ROOT));}
        catch(IllegalArgumentException e){throw new IllegalArgumentException("Unsupported production mode: "+raw,e);}
    }
}
