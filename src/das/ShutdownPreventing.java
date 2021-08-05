package das;

public interface ShutdownPreventing {
    boolean shutdownNotAllowed();
    String getID();
}
