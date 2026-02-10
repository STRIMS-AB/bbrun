package org.bbrun;

/**
 * Exception thrown by BBRun during parsing or execution.
 */
public class BBRunException extends RuntimeException {

    private final int line;
    private final String scriptPath;

    public BBRunException(String message) {
        super(message);
        this.line = -1;
        this.scriptPath = null;
    }

    public BBRunException(String message, Throwable cause) {
        super(message, cause);
        this.line = -1;
        this.scriptPath = null;
    }

    public BBRunException(String message, int line, String scriptPath) {
        super(formatMessage(message, line, scriptPath));
        this.line = line;
        this.scriptPath = scriptPath;
    }

    public BBRunException(String message, int line, String scriptPath, Throwable cause) {
        super(formatMessage(message, line, scriptPath), cause);
        this.line = line;
        this.scriptPath = scriptPath;
    }

    public int getLine() {
        return line;
    }

    public String getScriptPath() {
        return scriptPath;
    }

    private static String formatMessage(String message, int line, String scriptPath) {
        if (scriptPath != null && line > 0) {
            return String.format("%s (at %s:%d)", message, scriptPath, line);
        } else if (line > 0) {
            return String.format("%s (at line %d)", message, line);
        }
        return message;
    }
}
