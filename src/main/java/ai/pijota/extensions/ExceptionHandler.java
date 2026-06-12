package ai.pijota.extensions;

public interface ExceptionHandler {
    void onException(Throwable t, ExtensionContext ctx);
}
