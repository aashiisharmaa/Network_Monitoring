package com.network.monitoring.alarm.storage;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import static com.network.monitoring.alarm.exception.AlarmConfigurationException.invalid;
/** Resolves only administrator-approved input folders; does not schedule or import files. */
@Component
public class AlarmInputFolders {
    private final Path inputRoot;
    public AlarmInputFolders(@Value("${app.alarm-workflow.input-root:}") String inputRoot) {
        this.inputRoot=inputRoot==null||inputRoot.isBlank()?null:Path.of(inputRoot).toAbsolutePath().normalize();
    }
    public boolean configured() { return inputRoot!=null; }
    public Path resolve(String relative) {
        if(inputRoot==null) throw invalid("Configure ALARM_INPUT_ROOT before enabling folder watches.");
        if(relative==null||relative.isBlank()||Path.of(relative).isAbsolute()) throw invalid("Watch folder must be relative to ALARM_INPUT_ROOT.");
        Path candidate=inputRoot.resolve(relative).normalize();
        try {if(!candidate.startsWith(inputRoot)||!candidate.toRealPath().startsWith(inputRoot.toRealPath())||!Files.isDirectory(candidate)) throw invalid("Folder is outside the approved input root or is unavailable.");}
        catch(java.io.IOException e) {throw invalid("Approved input folder is unavailable.");}
        return candidate;
    }
}
