package autonewsroller.audit;
import autonewsroller.model.FactPackage;public final class SourceAudit {public void requireSources(FactPackage f){if(f.sources()==null||f.sources().isEmpty())throw new IllegalStateException("No source metadata");for(var s:f.sources())if(String.valueOf(s.getOrDefault("url","")).isBlank())throw new IllegalStateException("Source missing URL");}}
